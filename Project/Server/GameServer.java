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
    private static final long READY_MS = Math.max(1, Long.getLong("it114.readyMs", 30_000L));
    private static final long ROUND_MS = Math.max(1, Long.getLong("it114.roundMs", 30_000L));
    private static final int LAST_ROUND = 3;

    /** Private guess data; User retains shared identity, readiness, submitted status, and points. */
    private static final class SessionPlayer {
        final User user;
        int guess;

        SessionPlayer(User user) {
            this.user = user;
        }
    }

    private final Timer timer = new Timer("game-deadlines", true);
    private final Map<Long, SessionPlayer> players = new LinkedHashMap<>();
    private Phase phase = Phase.INACTIVE;
    private long generation;
    private int roundNumber;
    private int hiddenNumber;
    private final java.util.Random random = new java.util.Random(
            Long.getLong("it114.seed", System.nanoTime()));


    /** Apply a READY request forwarded by Server. The first accepted player starts the timed ready window.
     *
     * @param sender the requesting user
     */
    void handleReady(User sender) {
        if (phase != Phase.INACTIVE && phase != Phase.READY) {
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "Wait for the next ready check");
            return;
        }
        if (players.containsKey(sender.getClientId())) {
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "You are already marked ready");
            return;
        }
        sender.setReady(true);
        players.put(sender.getClientId(), new SessionPlayer(sender));
        if (phase == Phase.INACTIVE) {
            phase = Phase.READY;
            Server.INSTANCE.sendGameMessage("Ready check started; type /ready within " + ((READY_MS + 999) / 1000) + " seconds");
            Server.INSTANCE.sendGamePhaseToAll(phase);
            schedule(READY_MS);
        }
        Server.INSTANCE.sendReadyStatusToAll(sender.getClientId(), true);
    }

    /** Apply a GUESS request from Server; participants may revise guesses until the round closes.
     *
     * @param sender the requesting user
     * @param guess the requested number from 1 through 10
     */
    void handleGuess(User sender, int guess) {
        SessionPlayer player = players.get(sender.getClientId());
        if (phase != Phase.IN_PROGRESS || player == null) {
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "No active round for this player");
        } else if (guess < 1 || guess > 10) {
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "Use /guess <1-10>");
        } else {
            boolean firstGuess = !player.user.isSubmitted();
            player.user.setSubmitted(true);
            player.guess = guess;
            Server.INSTANCE.sendGuessConfirmationTo(sender.getClientId(), guess);
            if (firstGuess) {
                Server.INSTANCE.sendGuessStatusToAll(sender.getClientId(), true);
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
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "Only an active participant can reset");
            return;
        }
        resetSession("Session reset; type /ready to join again");
    }

    /** Apply the current phase or turn deadline under Server's monitor; ignore stale callbacks.
     *
     * @param expectedGeneration the scheduled deadline generation
     */
    void onTimer(long expectedGeneration) {
        if (expectedGeneration != generation) {
            return;
        }
        if (phase == Phase.READY) {
            if (players.size() < 2) {
                resetSession("At least two ready players are needed");
            } else {
                onSessionStart();
            }
        } else if (phase == Phase.IN_PROGRESS) {
            onRoundEnd();
        }
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
        Server.INSTANCE.sendReadyStatusToAll(client.getClientId(), false);
        if (players.isEmpty()) {
            resetSession("Session ended because all players left");
        } else if (phase == Phase.IN_PROGRESS && allGuessed()) {
            onRoundEnd();
        }
    }

    /** Replay public state to a newcomer; only its owner receives a previously submitted guess.
     *
     * @param recipientId the new connection
     */
    void syncTo(long recipientId) {
        Server.INSTANCE.sendGamePhaseTo(recipientId, phase);
        if (roundNumber > 0) {
            Server.INSTANCE.sendRoundTo(recipientId, roundNumber);
        }
        for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
            long id = entry.getKey();
            SessionPlayer player = entry.getValue();
            Server.INSTANCE.sendReadyStatusTo(recipientId, id, true);
            Server.INSTANCE.sendGuessStatusTo(recipientId, id, player.user.isSubmitted());
            Server.INSTANCE.sendPointsTo(recipientId, id, player.user.getPoints());
            if (id == recipientId && player.user.isSubmitted()) {
                Server.INSTANCE.sendGuessConfirmationTo(recipientId, player.guess);
            }
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

    /** Open a simultaneous round with a new hidden number and deadline; keep guesses private. */
    private void onRoundStart() {
        roundNumber++;
        hiddenNumber = random.nextInt(10) + 1;
        for (Map.Entry<Long, SessionPlayer> entry : players.entrySet()) {
            entry.getValue().user.setSubmitted(false);
            Server.INSTANCE.sendGuessStatusToAll(entry.getKey(), false);
        }
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGamePhaseToAll(phase);
        Server.INSTANCE.sendRoundToAll(roundNumber);
        Server.INSTANCE.sendGameMessage("Players have " + ((ROUND_MS + 999) / 1000) + " seconds to /guess <1-10>");
        schedule(ROUND_MS);
    }

    /** Evaluate each round once, award one point per exact match, publish totals, then advance or finish. */
    private void onRoundEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        generation++;
        phase = Phase.EVALUATION;
        Server.INSTANCE.sendGamePhaseToAll(phase);
        Server.INSTANCE.sendGameMessage("Round " + roundNumber + " number was " + hiddenNumber);
        for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
            SessionPlayer player = entry.getValue();
            if (player.user.isSubmitted() && player.guess == hiddenNumber) {
                player.user.setPoints(player.user.getPoints() + 1);
            }
            Server.INSTANCE.sendPointsToAll(entry.getKey(), player.user.getPoints());
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
            Server.INSTANCE.sendGameMessage("Highest score: " + high + " by " + String.join(", ", winners));
        }
        resetSession("Example session finished; type /ready to repeat");
    }

    /** Invalidate old deadlines, clear game state, and broadcast the public reset.
     *
     * @param message the public reset reason
     */
    private void resetSession(String message) {
        generation++;
        for (SessionPlayer player : new ArrayList<>(players.values())) {
            player.user.setReady(false);
            player.user.setSubmitted(false);
            player.user.setPoints(0);
            long id = player.user.getClientId();
            Server.INSTANCE.sendReadyStatusToAll(id, false);
            Server.INSTANCE.sendGuessStatusToAll(id, false);
            Server.INSTANCE.sendPointsToAll(id, 0);
        }
        players.clear();
        roundNumber = 0;
        hiddenNumber = 0;
        phase = Phase.INACTIVE;
        Server.INSTANCE.sendGamePhaseToAll(phase);
        Server.INSTANCE.sendGameMessage(message);
    }

    /**
     * Check whether every remaining participant acted this round.
     *
     * @return true when every remaining participant has acted
     */
    private boolean allGuessed() {
        return !players.isEmpty() && players.values().stream().allMatch(player -> player.user.isSubmitted());
    }

    /** Schedule a deadline through Server; later generations make older callbacks harmless.
     *
     * @param delayMs milliseconds until the deadline
     */
    private void schedule(long delayMs) {
        long expected = ++generation;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Server.INSTANCE.onGameTimer(expected);
            }
        }, delayMs);
    }
}
