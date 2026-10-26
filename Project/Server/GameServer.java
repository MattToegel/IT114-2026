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
    private final Server server;
    private final Timer timer = new Timer("game-deadlines", true);
    private final Map<Long, ServerThread> players = new LinkedHashMap<>();
    private Phase phase = Phase.INACTIVE;
    private long generation;

    GameServer(Server server) {
        this.server = server;
    }

    /** Register one ready client; a newcomer waits for the next session in play. */
    void handleReady(ServerThread sender) {
        if (phase == Phase.IN_PROGRESS) {
            sendMessageTo(sender, "Wait for the next ready check");
            return;
        }
        if (players.containsKey(sender.getClientId())) {
            sendMessageTo(sender, "You are already marked ready");
            return;
        }
        players.put(sender.getClientId(), sender);
        if (phase == Phase.INACTIVE) {
            phase = Phase.READY;
            sendMessage("Ready check started; type /ready within thirty seconds");
            sendPhaseToAll();
            schedule(READY_MS);
        }
        sendReadyStatusToAll(sender.getClientId(), true);
    }

    /** A participant can clear the example and start a fresh ready window. */
    void handleReset(ServerThread sender) {
        if (!players.containsKey(sender.getClientId())) {
            sendMessageTo(sender, "Only an active participant can reset");
            return;
        }
        resetSession("Session reset; type /ready to join again");
    }

    /** Apply an expired deadline only when it belongs to the current phase. */
    void onTimer(long expectedGeneration) {
        if (expectedGeneration != generation) {
            return;
        }
        if (phase == Phase.READY) {
            if (players.size() < 2) {
                resetSession("At least two ready players are needed");
            } else {
                phase = Phase.IN_PROGRESS;
                sendPhaseToAll();
                sendMessage("Example session started");
                schedule(SESSION_MS);
            }
        } else if (phase == Phase.IN_PROGRESS) {
            resetSession("Example session finished");
        }
    }

    /** Remove a disconnected participant; old timer work becomes harmless. */
    void onClientLeft(ServerThread client) {
        if (players.remove(client.getClientId()) == null) {
            return;
        }
        sendReadyStatusToAll(client.getClientId(), false);
        if (players.isEmpty()) {
            resetSession("Session ended because all players left");
        } else if (phase == Phase.IN_PROGRESS && players.size() < 2) {
            resetSession("Session ended because fewer than two players remain");
        }
    }

    /** Give a joiner the current phase and public readiness state. */
    void syncTo(ServerThread recipient) {
        sendPhaseTo(recipient);
        for (long id : players.keySet()) {
            sendReadyStatusTo(recipient, id, true);
        }
    }

    /** Invalidate the prior deadline and clear all participant state. */
    private void resetSession(String message) {
        generation++;
        for (long id : players.keySet()) {
            sendReadyStatusToAll(id, false);
        }
        players.clear();
        phase = Phase.INACTIVE;
        sendPhaseToAll();
        sendMessage(message);
    }

    /** Schedule one phase deadline; obsolete callbacks fail the generation check. */
    private void schedule(long delayMs) {
        long expected = ++generation;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                server.onGameTimer(expected);
            }
        }, delayMs);
    }

    /** Bundle a phase snapshot for one recipient. */
    private void sendPhaseTo(ServerThread recipient) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.GAME_PHASE_SYNC);
        payload.setMessage(phase.name());
        server.sendGameTo(recipient, payload);
    }

    /** Bundle the phase announcement for all clients. */
    private void sendPhaseToAll() {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.GAME_PHASE_SYNC);
        payload.setMessage(phase.name());
        server.sendGameBroadcast(payload);
    }

    /** Bundle one player's public ready status for one recipient. */
    private void sendReadyStatusTo(ServerThread recipient, long id, boolean ready) {
        BoolPayload payload = new BoolPayload();
        payload.setPayloadType(PayloadType.PLAYER_READY_STATUS);
        payload.setClientId(id);
        payload.setValue(ready);
        server.sendGameTo(recipient, payload);
    }

    /** Bundle one player's public ready status for all clients. */
    private void sendReadyStatusToAll(long id, boolean ready) {
        BoolPayload payload = new BoolPayload();
        payload.setPayloadType(PayloadType.PLAYER_READY_STATUS);
        payload.setClientId(id);
        payload.setValue(ready);
        server.sendGameBroadcast(payload);
    }

    /** Bundle one public explanation as a normal chat payload. */
    private void sendMessage(String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        server.sendGameBroadcast(payload);
    }

    /** Bundle one private explanation as a normal chat payload. */
    private void sendMessageTo(ServerThread recipient, String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        server.sendGameTo(recipient, payload);
    }
}
