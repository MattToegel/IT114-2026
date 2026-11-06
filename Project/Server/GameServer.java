package Project.Server;

import Project.Common.*;
import Project.Exceptions.IT114CustomException;
import Project.Exceptions.SessionNotInProgressException;
import Project.Exceptions.NotParticipantException;
import Project.Exceptions.AlreadySubmittedException;
import Project.Exceptions.NotYourTurnException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.List;

/**
 * Owns a three-round grid game. Server's monitor serializes requests,
 * disconnects, and deadlines; clients never choose cell results or points.
 */
final class GameServer {

    private static final long READY_MS = 30_000L;

    private static final long TURN_MS = 30_000L;

    private static final int LAST_ROUND = 3;

    private final Timer timer = new Timer(true);

    private final Map<Long, User> players = new LinkedHashMap<>();

    private Phase phase = Phase.INACTIVE;

    private TimerTask activeDeadline;

    private int roundNumber;

    private Grid grid;

    private long gridSeed;

    private long currentTurnPlayerId;

    /**
     * Reject game actions outside an active session before they can change game state.
     * @throws SessionNotInProgressException when the session is not in progress
     */
    private void checkSessionInProgress() throws SessionNotInProgressException {
        if (phase != Phase.IN_PROGRESS) {
            throw new SessionNotInProgressException();
        }
    }

    /**
     * Reject spectators so only participants from the current ready check can act.
     * @param sender the requesting user
     * @throws NotParticipantException when the user is a spectator
     */
    private void checkIsParticipant(User sender) throws NotParticipantException {
        if (!players.containsKey(sender.getClientId())) {
            throw new NotParticipantException();
        }
    }

    /**
     * Reject repeated actions to enforce the current one-action-per-round rule.
     * @param sender the requesting participant
     * @throws AlreadySubmittedException when the participant already acted
     */
    private void checkNotSubmitted(User sender) throws AlreadySubmittedException {
        if (sender.isSubmitted()) {
            throw new AlreadySubmittedException();
        }
    }

    /**
     * Reject actions from other players to enforce the server's current turn.
     * @param sender the requesting participant
     * @throws NotYourTurnException when another participant owns the turn
     */
    private void checkTakeTurn(User sender) throws NotYourTurnException {
        if (sender.getClientId() != currentTurnPlayerId) {
            throw new NotYourTurnException();
        }
    }

    /** Apply a READY request forwarded by Server. The first accepted player starts the timed ready window.
     *
     * @param sender the requesting user
     */
    void handleReady(User sender) {
        if (phase != Phase.INACTIVE && phase != Phase.READY) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Wait for the next ready check"));
            return;
        }
        if (players.containsKey(sender.getClientId())) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "You are already marked ready"));
            return;
        }
        sender.setReady(true);
        players.put(sender.getClientId(), sender);
        if (phase == Phase.INACTIVE) {
            phase = Phase.READY;
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Ready check started; type /ready within " + ((READY_MS + 999) / 1000) + " seconds"));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
            schedule(READY_MS);
        }
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(sender.getClientId(), true));
    }

    /** Apply a target plus pattern only for the current participant and a valid modifier.
     * @param sender the requesting user
     * @param x target column
     * @param y target row
     * @param modifier addition from 1 through 9
     */
    void handleGridAction(User sender, int x, int y, int modifier) {
        User player = players.get(sender.getClientId());
        try {
            checkSessionInProgress();
            checkIsParticipant(sender);
            checkNotSubmitted(sender);
            checkTakeTurn(sender);
            if (grid == null || !grid.contains(x, y) || modifier < 1 || modifier > 9) {
                sendMessageTo(sender.getClientId(), "Use /gridtest <x> <y> <value 1-9> inside the grid");
                return;
            }
            List<int[]> affected = grid.applyModifier(x, y, modifier);
            for (int[] position : affected) {
                sendGridCell(position[0], position[1]);
            }
            int gained = grid.countOdd(affected);
            player.setPoints(player.getPoints() + gained);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(sender.getClientId(), player.getPoints()));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID,
                    sender.getDisplayName() + " changed the plus pattern at (" + x + ", " + y
                    + ") and gained " + gained + " points"));
            player.setSubmitted(true);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(sender.getClientId(), true));
            onTurnEnd();
        } catch (IT114CustomException exception) {
            sendMessageTo(sender.getClientId(), exception.getMessage());
        }
    }

    /** Send an ordinary rejection only to its requester.
     * @param recipientId the requester
     * @param message explanation text
     */
    private void sendMessageTo(long recipientId, String message) {
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
    }

    /** Broadcast a confirmed cell after GameServer changes its authoritative grid.
     * @param x column
     * @param y row
     */
    private void sendGridCell(int x, int y) {
        int value = grid.getValue(x, y);
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGridCell(x, y, value));
    }

    /** Apply a RESET request forwarded by Server; only an active participant may clear the session.
     *
     * @param sender the requesting user
     */
    void handleReset(User sender) {
        if (!players.containsKey(sender.getClientId())) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Only an active participant can reset"));
            return;
        }
        resetSession("Session reset; type /ready to join again");
    }

    /**
     * Advance the current phase when its active deadline fires. A canceled callback
     * can already be waiting for that monitor and must then be ignored.
     *
     * @param fired the TimerTask invoking this callback
     */
    void onTimer(TimerTask fired) {
        if (fired != activeDeadline) {
            return;
        }
        activeDeadline = null;
        switch (phase) {
            case READY:
                onReadyExpire();
                break;
            case IN_PROGRESS:
                onTurnTimeout();
                break;
            case INACTIVE:
                break;
        }
    }

    /** Handle expiration of the ready window after onTimer validates its task. */
    private void onReadyExpire() {
        if (players.size() < 2) {
            resetSession("At least two ready players are needed");
        } else {
            onSessionStart();
        }
    }

    /** Start a repeatable seeded grid and the first round. */
    private void onSessionStart() {
        roundNumber = 0;
        for (User player : players.values()) {
            player.setPoints(0);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(player.getClientId(), player.getPoints()));
        }
        gridSeed = System.currentTimeMillis();
        grid = new Grid(5, 5, gridSeed);
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGridSeed(grid.getWidth(), grid.getHeight(), gridSeed));
        currentTurnPlayerId = 0;
        onRoundStart();
    }

    /** Update game state after Server removes a participant; advance or reset when needed.
     *
     * @param client the disconnected user
     */
    void onClientLeft(User client) {
        if (players.remove(client.getClientId()) == null) {
            return;
        }
        client.setReady(false);
        client.setSubmitted(false);
        client.setPoints(0);
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(client.getClientId(), false));
        if (players.isEmpty()) {
            resetSession("Session ended because all players left");
        } else if (phase == Phase.IN_PROGRESS) {
            if (client.getClientId() == currentTurnPlayerId) {
                onTurnEnd();
            } else if (allActed()) {
                onRoundEnd();
            }
        }
    }

    /** Sync authoritative public phase, scores, board, and turn to one newcomer.
     * @param recipientId the joining connection
     */
    void syncTo(long recipientId) {
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGamePhase(phase));
        if (roundNumber > 0) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendRound(roundNumber));
        }
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendCurrentTurn(currentTurnPlayerId));
        for (Map.Entry<Long, User> entry : players.entrySet()) {
            long id = entry.getKey();
            User player = entry.getValue();
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendReadyStatus(id, true));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendTurnStatus(id, player.isSubmitted()));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendPoints(id, player.getPoints()));
        }
        if (grid != null) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGridSeed(grid.getWidth(), grid.getHeight(), gridSeed));
            for (int y = 0; y < grid.getHeight(); y++) {
                for (int x = 0; x < grid.getWidth(); x++) {
                    int cellX = x;
                    int cellY = y;
                    int cellValue = grid.getValue(x, y);
                    Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGridCell(cellX, cellY, cellValue));
                }
            }
        }
    }

    /** Open a round in which each participant can make one grid action. */
    private void onRoundStart() {
        roundNumber++;
        for (Map.Entry<Long, User> entry : players.entrySet()) {
            entry.getValue().setSubmitted(false);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(entry.getKey(), false));
        }
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendRound(roundNumber));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID,
                "On your turn use /gridtest <x> <y> <value 1-9>"));
        onTurnStart();
    }

    /** Choose the lowest-ID participant who has not acted, then announce the owner and deadline once. */
    private void onTurnStart() {
        currentTurnPlayerId = players.entrySet().stream()
                .filter(entry -> !entry.getValue().isSubmitted())
                .mapToLong(Map.Entry::getKey)
                .min().orElse(0);
        if (currentTurnPlayerId == 0) {
            onRoundEnd();
            return;
        }
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(currentTurnPlayerId));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, players.get(currentTurnPlayerId).getDisplayName()
                + " has " + ((TURN_MS + 999) / 1000) + " seconds to /gridtest <x> <y> <value 1-9>"));
        schedule(TURN_MS);
    }

    /** Invalidate the finished turn deadline, then select another player or evaluate the round. */
    private void onTurnEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        cancelDeadline();
        currentTurnPlayerId = 0;
        if (allActed()) {
            onRoundEnd();
        } else {
            onTurnStart();
        }
    }

    /** Mark the owner as acted without a board change or point, then advance the turn. */
    private void onTurnTimeout() {
        User player = players.get(currentTurnPlayerId);
        if (player != null) {
            player.setSubmitted(true);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(currentTurnPlayerId, true));
        }
        onTurnEnd();
    }

    /** Finish a round; points were already awarded with each valid action. */
    private void onRoundEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        cancelDeadline();
        currentTurnPlayerId = 0;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(0));
        phase = Phase.EVALUATION;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID,
                "Round " + roundNumber + " complete"));
        if (roundNumber >= LAST_ROUND || players.isEmpty()) {
            onSessionEnd();
        } else {
            onRoundStart();
        }
    }

    /** Announce the highest public score, including ties, before clearing session points. */
    private void onSessionEnd() {
        if (!players.isEmpty()) {
            int high = players.values().stream().mapToInt(player -> player.getPoints()).max().orElse(0);
            List<String> winners = new ArrayList<>();
            for (User player : players.values()) {
                if (player.getPoints() == high) {
                    winners.add(player.getDisplayName());
                }
            }
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Highest score: " + high + " by " + String.join(", ", winners)));
        }
        resetSession("Example session finished; type /ready to repeat");
    }

    /** Invalidate old deadlines, clear game state, and broadcast the public reset.
     *
     * @param message the public reset reason
     */
    private void resetSession(String message) {
        cancelDeadline();
        for (User player : players.values()) {
            player.setReady(false);
            player.setSubmitted(false);
            player.setPoints(0);
            long id = player.getClientId();
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(id, false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(id, false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(id, 0));
        }
        players.clear();
        roundNumber = 0;
        grid = null;
        gridSeed = 0;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGridSeed(0, 0, 0));
        currentTurnPlayerId = 0;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(0));
        phase = Phase.INACTIVE;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
    }

    /**
     * Check whether every remaining participant acted this round.
     *
     * @return true when every remaining participant has acted
     */
    private boolean allActed() {
        return !players.isEmpty() && players.values().stream().allMatch(player -> player.isSubmitted());
    }

    /** Cancel the active deadline before a reset or phase/turn replacement. */
    private void cancelDeadline() {
        if (activeDeadline != null) {
            activeDeadline.cancel();
            activeDeadline = null;
        }
    }

    /**
     * Replace the deadline. Its callback holds the same Server lock as client actions
     * and finishes departure cleanup after the game event.
     *
     * @param delayMs milliseconds until the deadline
     */
    private void schedule(long delayMs) {
        cancelDeadline();
        activeDeadline = new TimerTask() {
            @Override
            public void run() {
                synchronized (Server.INSTANCE) {
                    try {
                        onTimer(this);
                    } finally {
                        Server.INSTANCE.processDisconnectedBuffer();
                    }
                }
            }
        };
        timer.schedule(activeDeadline, delayMs);
    }
}
