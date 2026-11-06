package Project.Server;

import Project.Common.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Owns a three-round grid-and-cards game. Server's monitor serializes requests,
 * disconnects, and deadlines; clients never choose cell results or points.
 */
final class GameServer {

    private static final long READY_MS = 30_000L;

    private static final long TURN_MS = 30_000L;

    private static final int LAST_ROUND = 3;

    private static final int HAND_SIZE = 3;

    /** Retains a participant User while connection ownership stays in Server. */
    private static final class SessionPlayer {
        final User user;
        final java.util.List<Integer> hand = new ArrayList<>();

        SessionPlayer(User user) {
            this.user = user;
        }
    }

    private final Timer timer = new Timer(true);

    private final Map<Long, SessionPlayer> players = new LinkedHashMap<>();

    private Phase phase = Phase.INACTIVE;

    private TimerTask activeDeadline;

    private int roundNumber;

    private Grid grid;

    private long gridSeed;

    private Deck deck;

    private long currentTurnPlayerId;

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

    /** Validate card ownership and target before applying the server-owned modifier.
     * @param sender the requesting user
     * @param cardId proposed card ID
     * @param x target column
     * @param y target row
     */
    void handleCardAction(User sender, int cardId, int x, int y) {
        SessionPlayer player = players.get(sender.getClientId());
        if (phase != Phase.IN_PROGRESS || player == null) {
            sendMessageTo(sender.getClientId(), "No active round for this player");
        } else if (player.user.isSubmitted()) {
            sendMessageTo(sender.getClientId(), "You already acted this round");
        } else if (sender.getClientId() != currentTurnPlayerId) {
            sendMessageTo(sender.getClientId(), "It is not your turn");
        } else if (grid == null || !grid.contains(x, y) || cardId < 1
                || deck == null || !player.hand.contains(cardId) || deck.find(cardId) == null) {
            sendMessageTo(sender.getClientId(), "Use /card <cardId> <x> <y> with a card in your hand and a grid cell");
        } else {
            Card card = deck.find(cardId);
            java.util.List<int[]> affected = grid.applyModifier(x, y, card.getModifier());
            for (int[] position : affected) {
                sendGridCell(position[0], position[1]);
            }
            int gained = grid.countOdd(affected);
            player.user.setPoints(player.user.getPoints() + gained);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(sender.getClientId(), player.user.getPoints()));
            player.hand.remove(Integer.valueOf(cardId));
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendCardHand(player.hand));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendCardCount(sender.getClientId(), player.hand.size()));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID,
                    sender.getDisplayName() + " played card " + cardId + " at (" + x + ", " + y
                    + ") and gained " + gained + " points"));
            player.user.setSubmitted(true);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(sender.getClientId(), true));
            onTurnEnd();
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
        for (SessionPlayer player : players.values()) {
            player.user.setPoints(0);
            player.hand.clear();
        }
        try {
            deck = new Deck(Path.of("Project/Server/cards.txt"));
        } catch (IOException e) {
            resetSession("Could not load the example card catalog: " + e.getMessage());
            return;
        }
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCardCatalog(deck.catalog()));
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
        SessionPlayer departed = players.remove(client.getClientId());
        if (departed == null) {
            return;
        }
        departed.hand.clear();
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCardCount(client.getClientId(), 0));
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
        for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
            long id = entry.getKey();
            SessionPlayer player = entry.getValue();
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendReadyStatus(id, true));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendTurnStatus(id, player.user.isSubmitted()));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendPoints(id, player.user.getPoints()));
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
        if (deck != null) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendCardCatalog(deck.catalog()));
            for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
                long id = entry.getKey();
                SessionPlayer player = entry.getValue();
                Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendCardCount(id, player.hand.size()));
                if (id == recipientId) {
                    Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendCardHand(player.hand));
                }
            }
        }
    }

    /** Open a round in which each participant can make one grid action. */
    private void onRoundStart() {
        roundNumber++;
        for (Map.Entry<Long, SessionPlayer> entry : players.entrySet()) {
            entry.getValue().user.setSubmitted(false);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(entry.getKey(), false));
        }
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendRound(roundNumber));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID,
                "On your turn use /card <cardId> <x> <y>"));
        onTurnStart();
    }

    /** Choose the lowest-ID participant who has not acted, then announce the owner and deadline once. */
    private void onTurnStart() {
        currentTurnPlayerId = players.entrySet().stream()
                .filter(entry -> !entry.getValue().user.isSubmitted())
                .mapToLong(Map.Entry::getKey)
                .min().orElse(0);
        if (currentTurnPlayerId == 0) {
            onRoundEnd();
            return;
        }
        SessionPlayer current = players.get(currentTurnPlayerId);
        while (current.hand.size() < HAND_SIZE) {
            current.hand.add(deck.draw());
        }
        Server.INSTANCE.sendTo(currentTurnPlayerId, recipient -> recipient.sendCardHand(current.hand));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCardCount(currentTurnPlayerId, current.hand.size()));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(currentTurnPlayerId));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, players.get(currentTurnPlayerId).user.getDisplayName()
                + " has " + ((TURN_MS + 999) / 1000) + " seconds to /card <cardId> <x> <y>"));
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
        SessionPlayer player = players.get(currentTurnPlayerId);
        if (player != null) {
            player.user.setSubmitted(true);
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
            player.hand.clear();
            Server.INSTANCE.sendTo(id, recipient -> recipient.sendCardHand(player.hand));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendCardCount(id, 0));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(id, false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendTurnStatus(id, false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(id, 0));
        }
        players.clear();
        roundNumber = 0;
        grid = null;
        gridSeed = 0;
        deck = null;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCardCatalog(java.util.List.of()));
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
