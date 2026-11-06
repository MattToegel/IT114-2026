package Project.Server;

import Project.Common.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Owns the small ready-check session. All methods run while holding Server's
 * monitor, including timer callbacks, so a request cannot race a deadline.
 */
final class GameServer {

    private static final long READY_MS = 30_000L;

    private final Timer timer = new Timer(true);

    private final Map<Long, User> players = new LinkedHashMap<>();

    // TODO: Remove the random generator and secret when implementing your project game.
    private final java.util.Random guessRandom = new java.util.Random();

    // TODO: Remove this temporary guessing-game field when implementing your project game.
    private int secret;

    private Phase phase = Phase.INACTIVE;

    private TimerTask activeDeadline;

    /** Apply a READY request forwarded by Server. A newcomer during play waits for the next session.
     *
     * @param sender the requesting user
     */
    void handleReady(User sender) {
        if (phase == Phase.IN_PROGRESS) {
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

    /**
     * Handle a numeric guess forwarded by Server.handleGuess() under the Server lock.
     * Only active participants can score.
     *
     * @param sender the user who entered /guess
     * @param guess the requested whole number from 1 through 5
     */
    void handleGuess(User sender, int guess) {
        // TODO: Remove this temporary handler when implementing your project game.
        if (phase != Phase.IN_PROGRESS || !players.containsKey(sender.getClientId())) {
            return;
        }
        if (guess < 1 || guess > 5) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(
                    Constants.DEFAULT_CLIENT_ID, "Choose a number from 1 to 5"));
            return;
        }
        if (guess != secret) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(
                    Constants.DEFAULT_CLIENT_ID, "Try again"));
            return;
        }
        sender.setPoints(sender.getPoints() + 1);
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(sender.getClientId(), sender.getPoints()));
        // TODO: Replace the five-point win condition with your project's scoring and end rules.
        if (sender.getPoints() == 5) {
            resetSession(sender.getDisplayName() + " wins with 5 points; type /ready to play again");
        } else {
            // TODO: Remove the next secret when replacing this temporary guessing game.
            secret = guessRandom.nextInt(5) + 1;
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(
                    Constants.DEFAULT_CLIENT_ID, "Correct! A new number is ready"));
        }
    }

    /** Apply a RESET request forwarded by Server; only an active participant may restart readiness.
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

    /** Start the session and publish each participant's initial score. */
    private void onSessionStart() {
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        for (User player : players.values()) {
            player.setPoints(0);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(player.getClientId(), player.getPoints()));
        }
        // TODO: Replace the secret and instructions with your project's session setup.
        secret = guessRandom.nextInt(5) + 1;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID,
                "Use /guess <value> with a number from 1 to 5; each correct guess earns one point. First to 5 points wins"));
    }

    /** Clear a disconnected participant's points; spectator departure has no game effect.
     *
     * @param client the disconnected user
     */
    void onClientLeft(User client) {
        if (players.remove(client.getClientId()) == null) {
            return;
        }
        client.setReady(false);
        client.setPoints(0);
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(client.getClientId(), false));
        if (players.isEmpty()) {
            resetSession("Session ended because all players left");
        } else if (phase == Phase.IN_PROGRESS && players.size() < 2) {
            resetSession("Session ended because fewer than two players remain");
        }
    }

    /** Replay public phase, readiness, and points to a newcomer, including a spectator.
     *
     * Uses the same ServerThread send methods as live updates to catch this one
     * client up with authoritative state. Sync selects current values and recipients.
     * Send methods bundle those values for transmission.
     *
     * @param recipientId the newly registered connection
     */
    void syncTo(long recipientId) {
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGamePhase(phase));
        for (long id : players.keySet()) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendReadyStatus(id, true));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendPoints(id, players.get(id).getPoints()));
        }
    }

    /** Invalidate old deadlines, clear readiness and points, and broadcast the reset state.
     *
     * @param message the reason broadcast to all connected users
     */
    private void resetSession(String message) {
        cancelDeadline();
        for (User user : players.values()) {
            user.setReady(false);
            user.setPoints(0);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(user.getClientId(), false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(user.getClientId(), 0));
        }
        players.clear();
        phase = Phase.INACTIVE;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
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
