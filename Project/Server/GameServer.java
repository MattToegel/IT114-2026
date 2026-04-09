package Project.Server;

import java.util.ArrayList;
import java.util.List;

import Project.Common.Constants;
import Project.Common.LoggerUtil;
import Project.Common.Phase;
import Project.Common.TimedEvent;
import Project.Common.ValidationUtils;
import Project.Exceptions.ValidationException;

/**
 * Concrete game session scaffold based on the old GameRoom lifecycle.
 *
 * Commands currently supported from clients:
 * - /ready
 * - /turn <action>
 */
public class GameServer extends BaseGameServer {

    private static final int MIN_PLAYERS_TO_START = 2;
    private static final int READY_SECONDS = 30;
    private static final int ROUND_SECONDS = 30;
    private static final int TURN_SECONDS = 20;
    private static final String GAME_TAG = "[Game] ";

    private volatile Phase phase = Phase.INACTIVE;
    private volatile TimedEvent readyTimer;
    private volatile TimedEvent roundTimer;
    private volatile TimedEvent turnTimer;
    private volatile Long currentTurnPlayerId;

    // start region for lifecycle hook implementations
    @Override
    protected void onPlayerJoined(ServerThread client) {
        if (client == null) {
            return;
        }

        LoggerUtil.INSTANCE.info("[GameServer] Player joined via ready: " + client.getDisplayName());
        unicastGameMessage(client, "Joined as active player. Waiting room status: "
                + getActivePlayerCount() + "/" + MIN_PLAYERS_TO_START + " ready.");
        unicastCurrentPhase(client);
        unicastGameStateToJoiner(client);
        broadcastGameMessage(client.getDisplayName() + " joined active players.");
        broadcastGameMessage("Active players: " + getActivePlayerCount());
    }

    @Override
    protected void onPlayerLeft(ServerThread client) {
        if (client == null) {
            return;
        }

        LoggerUtil.INSTANCE.info("[GameServer] Player left: " + client.getDisplayName());
        if (getActivePlayerCount() == 0) {
            resetReadyTimer();
            onSessionEnd();
            return;
        }
        if (phase == Phase.IN_PROGRESS && getActivePlayerCount() < MIN_PLAYERS_TO_START) {
            broadcastGameMessage("Not enough active players to continue.");
            onSessionEnd();
        }
    }

    @Override
    protected synchronized void onSessionStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onSessionStart() start");
        resetReadyTimer();
        phase = Phase.IN_PROGRESS;
        broadcastCurrentPhase();
        broadcastGameMessage("Session started.");
        LoggerUtil.INSTANCE.info("[GameServer] onSessionStart() end");
        onRoundStart();
    }

    @Override
    protected synchronized void onRoundStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() start");
        resetRoundTimer();
        startRoundTimer();

        for (ServerThread player : getActivePlayers()) {
            player.setTurnTaken(false);
            broadcastTurnStatus(player.getClientId(), false);
        }

        broadcastGameMessage("Round started. You have " + ROUND_SECONDS + "s total.");
        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() end");
        // onTurnStart(); this example doesn't use turns, all players take their
        // fictional turn simultaneously within the round time limit
    }

    @Override
    protected synchronized void onTurnStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onTurnStart() start");
        resetTurnTimer();

        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        if (snapshot.isEmpty()) {
            onSessionEnd();
            return;
        }
        // TODO: pick next player (covered in a future lesson, below is a temporary
        // scaffold that just picks the first active player)
        ServerThread chosen = snapshot.get(0); // simple scaffold: first active player
        currentTurnPlayerId = chosen.getClientId();

        startTurnTimer();
        broadcastGameMessage("Turn started for " + chosen.getDisplayName() + ". Use /turn <action> within "
                + TURN_SECONDS + "s.");
        LoggerUtil.INSTANCE.info("[GameServer] onTurnStart() end");
    }

    @Override
    protected synchronized void onTurnEnd() {
        LoggerUtil.INSTANCE.info("[GameServer] onTurnEnd() start");
        resetTurnTimer();
        currentTurnPlayerId = null;
        LoggerUtil.INSTANCE.info("[GameServer] onTurnEnd() end");
        // onRoundEnd(); this example doesn't use turns, but this hook is called at the
        // end of handleTurn() and we don't want it to end the round
    }

    @Override
    protected synchronized void onRoundEnd() {
        LoggerUtil.INSTANCE.info("[GameServer] onRoundEnd() start");
        resetRoundTimer();
        broadcastGameMessage("Round ended.");
        LoggerUtil.INSTANCE.info("[GameServer] onRoundEnd() end");
        // TODO: add logic to determine if session should end or next round should
        // start, below is a simple scaffold that just ends the session

        onSessionEnd();
    }

    @Override
    protected synchronized void onSessionEnd() {
        LoggerUtil.INSTANCE.info("[GameServer] onSessionEnd() start");
        resetReadyTimer();
        resetTurnTimer();
        resetRoundTimer();
        currentTurnPlayerId = null;
        phase = Phase.INACTIVE;

        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        // reset player data and sync changes to clients before clearing active players,
        // so that clients have a chance to update any relevant UI (like ready status)
        // before being removed from the session
        for (ServerThread player : snapshot) {
            player.setReady(false);
            player.setTurnTaken(false);
        }
        // default client id is used as a reset trigger, no need to individually sync
        // resets for each property
        broadcastReadyStatus(Constants.DEFAULT_CLIENT_ID, false);
        clearActivePlayers();

        broadcastCurrentPhase();
        broadcastGameMessage("Session ended. Type /ready to join the next session.");
        LoggerUtil.INSTANCE.info("[GameServer] onSessionEnd() end");
    }
    // end region for lifecycle hook implementations

    // Start region for timer handlers ===================================

    private synchronized void startReadyTimer(boolean resetOnTry) {
        if (phase != Phase.READY) {
            return;
        }
        if (resetOnTry) {
            resetReadyTimer();
        }
        if (readyTimer == null) {
            readyTimer = new TimedEvent(READY_SECONDS, this::checkReadyStatus);
            readyTimer.setTickCallback(time -> LoggerUtil.INSTANCE.info("[GameServer] Ready timer: " + time));
            broadcastGameMessage(
                    "Ready check started. Session begins in " + READY_SECONDS + "s if enough players are ready.");
        }
    }

    private synchronized void resetReadyTimer() {
        if (readyTimer != null) {
            readyTimer.cancel();
            readyTimer = null;
        }
    }

    private synchronized void startRoundTimer() {
        roundTimer = new TimedEvent(ROUND_SECONDS, this::onRoundEnd);
        roundTimer.setTickCallback(time -> LoggerUtil.INSTANCE.info("[GameServer] Round timer: " + time));
    }

    private synchronized void resetRoundTimer() {
        if (roundTimer != null) {
            roundTimer.cancel();
            roundTimer = null;
        }
    }

    private synchronized void startTurnTimer() {
        turnTimer = new TimedEvent(TURN_SECONDS, this::onTurnEnd);
        turnTimer.setTickCallback(time -> LoggerUtil.INSTANCE.info("[GameServer] Turn timer: " + time));
    }

    private synchronized void resetTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
        }
    }

    // End region for timer handlers ===================================
    private synchronized void checkReadyStatus() {
        if (phase != Phase.READY) {
            return;
        }
        if (getActivePlayerCount() >= MIN_PLAYERS_TO_START) {
            onSessionStart();
        } else {
            broadcastGameMessage("Ready check expired: not enough ready players.");
            onSessionEnd();
        }
    }

    // start region for handle*() methods called by Server

    /**
     * Handles a player's ready action. Validates the action, registers them as an
     * active player, and starts the ready timer. Sends an error message back to the
     * player on failure.
     */
    public void handleReady(ServerThread sender) {
        try {
            ValidationUtils.requirePhaseAtMost(phase, Phase.READY);
            ValidationUtils.requireNotAlreadyReady(isActivePlayer(sender));

            if (phase == Phase.INACTIVE) {
                phase = Phase.READY;
                broadcastCurrentPhase();
            }

            sender.resetGameState();
            sender.setReady(true);
            if (addActivePlayer(sender)) {
                onPlayerJoined(sender);
            }

            broadcastReadyStatus(sender.getClientId(), true);
            broadcastGameMessage(sender.getDisplayName() + " is ready. (" + getActivePlayerCount() + " active)");
            startReadyTimer(false);
        } catch (ValidationException e) {
            LoggerUtil.INSTANCE.warning("[GameServer] " + e.getMessage());
            unicastGameMessage(sender, e.getMessage());
        }
    }

    /**
     * Handles a player's turn action. Validates the action, records the turn, and
     * advances the game. Sends an error message back to the player on failure.
     */
    public void handleTurn(ServerThread sender, String action) {
        try {
            ValidationUtils.requireParticipating(isActivePlayer(sender));
            ValidationUtils.requirePhase(phase, Phase.IN_PROGRESS);
            ValidationUtils.requireTurnNotTaken(sender.isTurnTaken());
            String normalizedAction = ValidationUtils.requireValidTurnOption(action);

            // TODO: turn logic would go here, in this example we're just marking that we
            // took a turn
            // ValidationUtils.requireCurrentPlayer(currentTurnPlayerId,
            // sender.getClientId());
            sender.setTurnTaken(true);
            broadcastTurnStatus(sender.getClientId(), true);
            broadcastGameMessage(sender.getDisplayName() + " played turn action: " + normalizedAction);
            onTurnEnd();
        } catch (ValidationException e) {
            LoggerUtil.INSTANCE.warning("[GameServer] " + e.getMessage());
            unicastGameMessage(sender, e.getMessage());
        }
    }

    // end region for handle*() methods called by Server

    // start region for helper methods to send data to clients

    /**
     * Sends all existing active players' ready and turn states to a newly joined
     * player.
     */
    private void unicastGameStateToJoiner(ServerThread joiner) {
        if (joiner == null) {
            return;
        }
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        for (ServerThread player : snapshot) {
            if (player.getClientId() == joiner.getClientId()) {
                continue;
            }
            unicastReadyStatus(joiner, player.getClientId(), player.isReady());
            unicastTurnStatus(joiner, player.getClientId(), player.isTurnTaken());
        }
    }

    /** Sends the current game phase to all connected clients. */
    private void broadcastCurrentPhase() {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendGamePhase(phase));
    }

    /** Sends the current game phase to a single client. */
    private void unicastCurrentPhase(ServerThread target) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendGamePhase(phase));
    }

    /** Notifies all connected clients of a player's ready status. */
    private void broadcastReadyStatus(long clientId, boolean isReady) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendReadyStatus(clientId, isReady));
    }

    /** Sends a player's ready status to a single client. */
    private void unicastReadyStatus(ServerThread target, long clientId, boolean isReady) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendReadyStatus(clientId, isReady));
    }

    /** Notifies all connected clients of a player's turn-taken status. */
    private void broadcastTurnStatus(long clientId, boolean hasTakenTurn) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendTurnStatus(clientId, hasTakenTurn));
    }

    /** Sends a player's turn-taken status to a single client. */
    private void unicastTurnStatus(ServerThread target, long clientId, boolean hasTakenTurn) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendTurnStatus(clientId, hasTakenTurn));
    }

    /** Sends a game message to all connected clients. */
    private void broadcastGameMessage(String message) {
        Server.INSTANCE.broadcast(null, GAME_TAG + message);
    }

    /** Sends a game message to a single client. */
    private void unicastGameMessage(ServerThread target, String message) {
        if (target == null) {
            return;
        }
        final String formatted = GAME_TAG + message;
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendMessage(formatted));
    }

    // end region for helper methods to send data to clients
}
