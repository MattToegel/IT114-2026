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

    private static final long SESSION_MS = 8_000L;


    private final Timer timer = new Timer(true);

    private final Map<Long, User> players = new LinkedHashMap<>();

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
                onSessionExpire();
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

    /** Finish the timed example session when onTimer receives its active deadline. */
    private void onSessionExpire() {
        resetSession("Example session finished");
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

    /**
     * Start the practice session once after at least two players finish readiness.
     * Award each participant one start bonus; this is replaced by guess scoring next lesson.
     */
    private void onSessionStart() {
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        for (User player : players.values()) {
            player.setPoints(1);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(player.getClientId(), player.getPoints()));
        }
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Practice session started; each ready player earned one start point"));
        schedule(SESSION_MS);
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
