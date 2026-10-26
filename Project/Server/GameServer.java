package Project.Server;

import Project.Common.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Owns a three-round grid example. Server's monitor serializes requests,
 * disconnects, and deadlines; clients never choose cell results or points.
 */
final class GameServer {
    private static final long READY_MS = Math.max(1, Long.getLong("it114.readyMs", 30_000L));
    private static final long TURN_MS = Math.max(1, Long.getLong("it114.turnMs", 30_000L));
    private static final int LAST_ROUND = 3;

    /** Server-only game state; public User remains the connection identity. */
    private static final class SessionPlayer {
        final ServerThread connection;
        boolean acted;
        int points;

        SessionPlayer(ServerThread connection) {
            this.connection = connection;
        }
    }

    private final Server server;
    private final Timer timer = new Timer("game-deadlines", true);
    private final Map<Long, SessionPlayer> players = new LinkedHashMap<>();
    private Phase phase = Phase.INACTIVE;
    private long generation;
    private int roundNumber;
    private Grid grid;
    private long gridSeed;
    private long currentTurnPlayerId;

    GameServer(Server server) {
        this.server = server;
    }

    /** Record one participant during the ready window. */
    void handleReady(ServerThread sender) {
        if (phase != Phase.INACTIVE && phase != Phase.READY) {
            sendMessageTo(sender, "Wait for the next ready check");
            return;
        }
        if (players.containsKey(sender.getClientId())) {
            sendMessageTo(sender, "You are already marked ready");
            return;
        }
        players.put(sender.getClientId(), new SessionPlayer(sender));
        if (phase == Phase.INACTIVE) {
            phase = Phase.READY;
            sendMessage("Ready check started; type /ready within thirty seconds");
            sendPhaseToAll();
            schedule(READY_MS);
        }
        sendReadyStatusToAll(sender.getClientId(), true);
    }

    /** Validate one plus-pattern action before changing server-owned cells. */
    void handleGridAction(ServerThread sender, int x, int y, int modifier) {
        SessionPlayer player = players.get(sender.getClientId());
        if (phase != Phase.IN_PROGRESS || player == null) {
            sendMessageTo(sender, "No active round for this player");
        } else if (player.acted) {
            sendMessageTo(sender, "You already acted this round");
        } else if (sender.getClientId() != currentTurnPlayerId) {
            sendMessageTo(sender, "It is not your turn");
        } else if (grid == null || !grid.contains(x, y) || modifier < 1 || modifier > 9) {
            sendMessageTo(sender, "Use /gridtest <x> <y> <value 1-9> inside the grid");
        } else {
            java.util.List<int[]> affected = grid.applyModifier(x, y, modifier);
            for (int[] position : affected) {
                sendGridCellToAll(position[0], position[1]);
            }
            int gained = grid.countOdd(affected);
            player.points += gained;
            sendPointsToAll(sender.getClientId(), player.points);
            sendMessage(sender.getDisplayName() + " changed the plus pattern at ("
                    + x + ", " + y + ") and gained " + gained + " points");
            player.acted = true;
            sendTurnStatusToAll(sender.getClientId(), true);
            onTurnEnd();
        }
    }

    /** Let a participant end this example session and clear its state. */
    void handleReset(ServerThread sender) {
        if (!players.containsKey(sender.getClientId())) {
            sendMessageTo(sender, "Only an active participant can reset");
            return;
        }
        resetSession("Session reset; type /ready to join again");
    }

    /** Discard stale timer callbacks before applying a phase deadline. */
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
            onTurnTimeout();
        }
    }

    /** Remove a participant and keep the current round moving. */
    void onClientLeft(ServerThread client) {
        if (players.remove(client.getClientId()) == null) {
            return;
        }
        sendReadyStatusToAll(client.getClientId(), false);
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

    /** Replay phase, turn, scores, seed, and authoritative cells to a joiner. */
    void syncTo(ServerThread recipient) {
        sendPhaseTo(recipient);
        if (roundNumber > 0) {
            sendRoundTo(recipient, roundNumber);
        }
        sendCurrentTurnTo(recipient, currentTurnPlayerId);
        for (Map.Entry<Long, SessionPlayer> entry : new ArrayList<>(players.entrySet())) {
            long id = entry.getKey();
            SessionPlayer player = entry.getValue();
            sendReadyStatusTo(recipient, id, true);
            sendTurnStatusTo(recipient, id, player.acted);
            sendPointsTo(recipient, id, player.points);
        }
        if (grid != null) {
            sendGridSeedTo(recipient);
            for (int y = 0; y < grid.getHeight(); y++) {
                for (int x = 0; x < grid.getWidth(); x++) {
                    sendGridCellTo(recipient, x, y);
                }
            }
        }
    }

    /** Initialize one repeatable session. */
    private void onSessionStart() {
        roundNumber = 0;
        for (SessionPlayer player : players.values()) {
            player.points = 0;
        }
        gridSeed = System.currentTimeMillis();
        grid = new Grid(5, 5, gridSeed);
        sendGridSeedToAll();
        currentTurnPlayerId = 0;
        onRoundStart();
    }

    /** Open a round of sequential grid actions. */
    private void onRoundStart() {
        roundNumber++;
        for (Map.Entry<Long, SessionPlayer> entry : players.entrySet()) {
            entry.getValue().acted = false;
            sendTurnStatusToAll(entry.getKey(), false);
        }
        phase = Phase.IN_PROGRESS;
        sendPhaseToAll();
        sendRoundToAll(roundNumber);
        sendMessage("On your turn use /gridtest <x> <y> <value 1-9>");
        onTurnStart();
    }

    /** Choose the first unacted player in stable client-ID order. */
    private void onTurnStart() {
        currentTurnPlayerId = players.entrySet().stream()
                .filter(entry -> !entry.getValue().acted)
                .mapToLong(Map.Entry::getKey)
                .min().orElse(0);
        if (currentTurnPlayerId == 0) {
            onRoundEnd();
            return;
        }
        sendCurrentTurnToAll(currentTurnPlayerId);
        schedule(TURN_MS);
    }

    /** Complete one turn and select another player or evaluate the round. */
    private void onTurnEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        generation++;
        currentTurnPlayerId = 0;
        if (allActed()) {
            onRoundEnd();
        } else {
            onTurnStart();
        }
    }

    /** A timed-out turn counts as taken without a cell change or points. */
    private void onTurnTimeout() {
        SessionPlayer player = players.get(currentTurnPlayerId);
        if (player != null) {
            player.acted = true;
            sendTurnStatusToAll(currentTurnPlayerId, true);
        }
        onTurnEnd();
    }

    /** Finish the round; points were awarded with each confirmed action. */
    private void onRoundEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        generation++;
        currentTurnPlayerId = 0;
        sendCurrentTurnToAll(0);
        phase = Phase.EVALUATION;
        sendPhaseToAll();
        sendMessage("Round " + roundNumber + " complete");
        if (roundNumber >= LAST_ROUND || players.isEmpty()) {
            onSessionEnd();
        } else {
            onRoundStart();
        }
    }

    /** Announce the highest score, including ties, before clearing points. */
    private void onSessionEnd() {
        if (!players.isEmpty()) {
            int high = players.values().stream().mapToInt(player -> player.points).max().orElse(0);
            java.util.List<String> winners = new ArrayList<>();
            for (SessionPlayer player : players.values()) {
                if (player.points == high) {
                    winners.add(player.connection.getDisplayName());
                }
            }
            sendMessage("Highest score: " + high + " by " + String.join(", ", winners));
        }
        resetSession("Example session finished; type /ready to repeat");
    }

    /** Invalidate old timers and clear all state for a new ready window. */
    private void resetSession(String message) {
        generation++;
        for (long id : new ArrayList<>(players.keySet())) {
            sendReadyStatusToAll(id, false);
            sendTurnStatusToAll(id, false);
            sendPointsToAll(id, 0);
        }
        players.clear();
        roundNumber = 0;
        grid = null;
        gridSeed = 0;
        sendGridResetToAll();
        currentTurnPlayerId = 0;
        sendCurrentTurnToAll(0);
        phase = Phase.INACTIVE;
        sendPhaseToAll();
        sendMessage(message);
    }

    private boolean allActed() {
        return !players.isEmpty() && players.values().stream().allMatch(player -> player.acted);
    }

    /** A new deadline supersedes the prior deadline's generation. */
    private void schedule(long delayMs) {
        long expected = ++generation;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                server.onGameTimer(expected);
            }
        }, delayMs);
    }

    /** Send the current phase to one client. */
    private void sendPhaseTo(ServerThread recipient) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.GAME_PHASE_SYNC);
        payload.setMessage(phase.name());
        server.sendGameTo(recipient, payload);
    }

    /** Broadcast the current phase. */
    private void sendPhaseToAll() {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.GAME_PHASE_SYNC);
        payload.setMessage(phase.name());
        server.sendGameBroadcast(payload);
    }

    /** Send one ready flag privately as a state snapshot. */
    private void sendReadyStatusTo(ServerThread recipient, long id, boolean ready) {
        BoolPayload payload = new BoolPayload();
        payload.setPayloadType(PayloadType.PLAYER_READY_STATUS);
        payload.setClientId(id);
        payload.setValue(ready);
        server.sendGameTo(recipient, payload);
    }

    /** Broadcast one ready flag. */
    private void sendReadyStatusToAll(long id, boolean ready) {
        BoolPayload payload = new BoolPayload();
        payload.setPayloadType(PayloadType.PLAYER_READY_STATUS);
        payload.setClientId(id);
        payload.setValue(ready);
        server.sendGameBroadcast(payload);
    }

    /** Send one action-completed flag privately as a state snapshot. */
    private void sendTurnStatusTo(ServerThread recipient, long id, boolean acted) {
        BoolPayload payload = new BoolPayload();
        payload.setPayloadType(PayloadType.TURN_STATUS);
        payload.setClientId(id);
        payload.setValue(acted);
        server.sendGameTo(recipient, payload);
    }

    /** Broadcast a player's action-completed flag. */
    private void sendTurnStatusToAll(long id, boolean acted) {
        BoolPayload payload = new BoolPayload();
        payload.setPayloadType(PayloadType.TURN_STATUS);
        payload.setClientId(id);
        payload.setValue(acted);
        server.sendGameBroadcast(payload);
    }

    /** Sync the seeded starting grid to one newcomer. */
    private void sendGridSeedTo(ServerThread recipient) {
        GridSeedPayload payload = new GridSeedPayload();
        payload.setPayloadType(PayloadType.GRID_SEED_SYNC);
        payload.setWidth(grid.getWidth());
        payload.setHeight(grid.getHeight());
        payload.setSeed(gridSeed);
        server.sendGameTo(recipient, payload);
    }

    /** Announce the seeded starting grid to all connected clients. */
    private void sendGridSeedToAll() {
        GridSeedPayload payload = new GridSeedPayload();
        payload.setPayloadType(PayloadType.GRID_SEED_SYNC);
        payload.setWidth(grid.getWidth());
        payload.setHeight(grid.getHeight());
        payload.setSeed(gridSeed);
        server.sendGameBroadcast(payload);
    }

    /** Clear the client grid at session end. */
    private void sendGridResetToAll() {
        GridSeedPayload payload = new GridSeedPayload();
        payload.setPayloadType(PayloadType.GRID_SEED_SYNC);
        server.sendGameBroadcast(payload);
    }

    /** Send one authoritative cell value to a newcomer. */
    private void sendGridCellTo(ServerThread recipient, int x, int y) {
        GridCellPayload payload = new GridCellPayload();
        payload.setPayloadType(PayloadType.GRID_CELL_SYNC);
        payload.setX(x);
        payload.setY(y);
        payload.setValue(grid.getValue(x, y));
        server.sendGameTo(recipient, payload);
    }

    /** Broadcast one server-confirmed changed cell. */
    private void sendGridCellToAll(int x, int y) {
        GridCellPayload payload = new GridCellPayload();
        payload.setPayloadType(PayloadType.GRID_CELL_SYNC);
        payload.setX(x);
        payload.setY(y);
        payload.setValue(grid.getValue(x, y));
        server.sendGameBroadcast(payload);
    }

    /** Send one player's public points as a snapshot. */
    private void sendPointsTo(ServerThread recipient, long id, int points) {
        IntPayload payload = new IntPayload();
        payload.setPayloadType(PayloadType.POINTS);
        payload.setClientId(id);
        payload.setValue(points);
        server.sendGameTo(recipient, payload);
    }

    /** Broadcast a player's current points. */
    private void sendPointsToAll(long id, int points) {
        IntPayload payload = new IntPayload();
        payload.setPayloadType(PayloadType.POINTS);
        payload.setClientId(id);
        payload.setValue(points);
        server.sendGameBroadcast(payload);
    }

    /** Send the current round to a newcomer. */
    private void sendRoundTo(ServerThread recipient, int round) {
        IntPayload payload = new IntPayload();
        payload.setPayloadType(PayloadType.ROUND_SYNC);
        payload.setValue(round);
        server.sendGameTo(recipient, payload);
    }

    /** Broadcast the new round number. */
    private void sendRoundToAll(int round) {
        IntPayload payload = new IntPayload();
        payload.setPayloadType(PayloadType.ROUND_SYNC);
        payload.setValue(round);
        server.sendGameBroadcast(payload);
    }

    /** Sync the current turn owner to one newcomer. */
    private void sendCurrentTurnTo(ServerThread recipient, long id) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.CURRENT_TURN);
        payload.setClientId(id);
        server.sendGameTo(recipient, payload);
    }

    /** Announce the one participant allowed to act. */
    private void sendCurrentTurnToAll(long id) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.CURRENT_TURN);
        payload.setClientId(id);
        server.sendGameBroadcast(payload);
    }

    /** Broadcast ordinary explanation text. */
    private void sendMessage(String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        server.sendGameBroadcast(payload);
    }

    /** Send an ordinary explanation only to its requester. */
    private void sendMessageTo(ServerThread recipient, String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        server.sendGameTo(recipient, payload);
    }
}
