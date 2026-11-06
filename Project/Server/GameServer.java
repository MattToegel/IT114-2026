package Project.Server;

import Project.Common.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Owns a three-round number guesser. Server's monitor serializes requests,
 * disconnects, and deadlines; clients never choose the hidden number or score.
 */
final class GameServer {

    private static final long READY_MS = 30_000L;

    private static final long ROUND_MS = 30_000L;

    private static final int LAST_ROUND = 3;

    /** Private guess data; User retains shared identity, readiness, submitted status, and points. */
    private static final class SessionPlayer {
        final User user;
        int guess;

        SessionPlayer(User user) {
            this.user = user;
        }
    }

    private final Timer timer = new Timer(true);

    private final Map<Long, SessionPlayer> players = new LinkedHashMap<>();

    private Phase phase = Phase.INACTIVE;

    private TimerTask activeDeadline;

    private int roundNumber;

    private int hiddenNumber;

    private final java.util.Random random = new java.util.Random();

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
        players.put(sender.getClientId(), new SessionPlayer(sender));
        if (phase == Phase.INACTIVE) {
            phase = Phase.READY;
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Ready check started; type /ready within " + ((READY_MS + 999) / 1000) + " seconds"));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
            schedule(READY_MS);
        }
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(sender.getClientId(), true));
    }

    /** Apply a GUESS request from Server; participants may revise guesses until the round closes.
     *
     * @param sender the requesting user
     * @param guess the requested number from 1 through 10
     */
    void handleGuess(User sender, int guess) {
        SessionPlayer player = players.get(sender.getClientId());
        if (phase != Phase.IN_PROGRESS || player == null) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "No active round for this player"));
        } else if (guess < 1 || guess > 10) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Use /guess <1-10>"));
        } else {
            boolean firstGuess = !player.user.isSubmitted();
            player.user.setSubmitted(true);
            player.guess = guess;
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendGuessConfirmation(guess));
            if (firstGuess) {
                Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(sender.getClientId(), true));
            }
            if (allGuessed()) {
                onRoundEnd();
            }
        }
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
                onRoundEnd();
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

    /** Begin a three-round session after readiness closes; clear prior points and open round one. */
    private void onSessionStart() {
        roundNumber = 0;
        for (SessionPlayer player : players.values()) {
            player.user.setPoints(0);
        }
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
        } else if (phase == Phase.IN_PROGRESS && allGuessed()) {
            onRoundEnd();
        }
    }

    /** Replay public state to a newcomer; only its owner receives a previously submitted guess.
     *
     * Uses the same ServerThread send methods as live updates to catch this one
     * client up with authoritative state. Sync selects current values and recipients.
     * Send methods bundle those values for transmission.
     *
     * @param recipientId the new connection
     */
    void syncTo(long recipientId) {
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGamePhase(phase));
        if (roundNumber > 0) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendRound(roundNumber));
        }
        for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
            long id = entry.getKey();
            SessionPlayer player = entry.getValue();
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendReadyStatus(id, true));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGuessStatus(id, player.user.isSubmitted()));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendPoints(id, player.user.getPoints()));
            if (id == recipientId && player.user.isSubmitted()) {
                Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGuessConfirmation(player.guess));
            }
        }
    }

    /** Open a simultaneous round with a new hidden number and deadline; keep guesses private. */
    private void onRoundStart() {
        roundNumber++;
        hiddenNumber = random.nextInt(10) + 1;
        for (Map.Entry<Long, SessionPlayer> entry : players.entrySet()) {
            entry.getValue().user.setSubmitted(false);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(entry.getKey(), false));
        }
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendRound(roundNumber));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Players have " + ((ROUND_MS + 999) / 1000) + " seconds to /guess <1-10>"));
        schedule(ROUND_MS);
    }

    /** Evaluate each round once, award one point per exact match, publish totals, then advance or finish. */
    private void onRoundEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        cancelDeadline();
        phase = Phase.EVALUATION;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Round " + roundNumber + " number was " + hiddenNumber));
        for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
            SessionPlayer player = entry.getValue();
            if (player.user.isSubmitted() && player.guess == hiddenNumber) {
                player.user.setPoints(player.user.getPoints() + 1);
            }
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(entry.getKey(), player.user.getPoints()));
        }
        if (roundNumber >= LAST_ROUND || players.isEmpty()) {
            onSessionEnd();
        } else {
            onRoundStart();
        }
    }

    /** Announce the highest public score, including ties, before clearing session points. */
    private void onSessionEnd() {
        if (!players.isEmpty()) {
            int high = players.values().stream().mapToInt(player -> player.user.getPoints()).max().orElse(0);
            java.util.List<String> winners = new ArrayList<>();
            for (SessionPlayer player : players.values()) {
                if (player.user.getPoints() == high) {
                    winners.add(player.user.getDisplayName());
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
        for (SessionPlayer player : new ArrayList<>(players.values())) {
            player.user.setReady(false);
            player.user.setSubmitted(false);
            player.user.setPoints(0);
            long id = player.user.getClientId();
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(id, false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(id, false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(id, 0));
        }
        players.clear();
        roundNumber = 0;
        hiddenNumber = 0;
        phase = Phase.INACTIVE;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
    }

    /**
     * Check whether every remaining participant acted this round.
     *
     * @return true when every remaining participant has acted
     */
    private boolean allGuessed() {
        return !players.isEmpty() && players.values().stream().allMatch(player -> player.user.isSubmitted());
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
