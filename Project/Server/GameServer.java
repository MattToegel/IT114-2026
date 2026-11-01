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

    private static final long READY_MS = Math.max(1, Long.getLong("it114.readyMs", 30_000L));

    private static final long SESSION_MS = Math.max(1, Long.getLong("it114.sessionMs", 8000L));


    private final Timer timer = new Timer("game-deadlines", true);

    private final Map<Long, User> players = new LinkedHashMap<>();

    private Phase phase = Phase.INACTIVE;

    private long generation;


    /** Apply a READY request forwarded by Server. A newcomer during play waits for the next session.
     *
     * @param sender the requesting user
     */
    void handleReady(User sender) {
        if (phase == Phase.IN_PROGRESS) {
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "Wait for the next ready check");
            return;
        }
        if (players.containsKey(sender.getClientId())) {
            Server.INSTANCE.sendGameMessageTo(sender.getClientId(), "You are already marked ready");
            return;
        }
        sender.setReady(true);
        players.put(sender.getClientId(), sender);
        if (phase == Phase.INACTIVE) {
            phase = Phase.READY;
            Server.INSTANCE.sendGameMessage("Ready check started; type /ready within " + ((READY_MS + 999) / 1000) + " seconds");
            Server.INSTANCE.sendGamePhaseToAll(phase);
            schedule(READY_MS);
        }
        Server.INSTANCE.sendReadyStatusToAll(sender.getClientId(), true);
    }

    /** Apply a RESET request forwarded by Server; only an active participant may restart readiness.
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

    /** Apply a deadline under Server's monitor, ignoring callbacks from older generations.
     *
     * @param expectedGeneration the generation captured when the deadline was scheduled
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
            resetSession("Example session finished");
        }
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
        Server.INSTANCE.sendReadyStatusToAll(client.getClientId(), false);
        if (players.isEmpty()) {
            resetSession("Session ended because all players left");
        } else if (phase == Phase.IN_PROGRESS && players.size() < 2) {
            resetSession("Session ended because fewer than two players remain");
        }
    }

    /** Replay public phase, readiness, and points to a newcomer, including a spectator.
     *
     * @param recipientId the newly registered connection
     */
    void syncTo(long recipientId) {
        Server.INSTANCE.sendGamePhaseTo(recipientId, phase);
        for (long id : players.keySet()) {
            Server.INSTANCE.sendReadyStatusTo(recipientId, id, true);
            Server.INSTANCE.sendPointsTo(recipientId, id, players.get(id).getPoints());
        }
    }

    /**
     * Start the practice session once after at least two players finish readiness.
     * Award each participant one start bonus; this is replaced by guess scoring next lesson.
     */
    private void onSessionStart() {
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGamePhaseToAll(phase);
        for (User player : players.values()) {
            player.setPoints(1);
            Server.INSTANCE.sendPointsToAll(player.getClientId(), player.getPoints());
        }
        Server.INSTANCE.sendGameMessage("Practice session started; each ready player earned one start point");
        schedule(SESSION_MS);
    }

    /** Invalidate old deadlines, clear readiness and points, and broadcast the reset state.
     *
     * @param message the reason broadcast to all connected users
     */
    private void resetSession(String message) {
        generation++;
        for (User user : players.values()) {
            user.setReady(false);
            user.setPoints(0);
            Server.INSTANCE.sendReadyStatusToAll(user.getClientId(), false);
            Server.INSTANCE.sendPointsToAll(user.getClientId(), 0);
        }
        players.clear();
        phase = Phase.INACTIVE;
        Server.INSTANCE.sendGamePhaseToAll(phase);
        Server.INSTANCE.sendGameMessage(message);
    }

    /** Schedule a deadline through Server; later generations make older callbacks harmless.
     *
     * @param delayMs milliseconds until this deadline
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
