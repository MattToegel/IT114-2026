package Clicky.Server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import Clicky.Common.Constants;
import Clicky.Common.LoggerUtil;
import Clicky.Common.Phase;
import Clicky.Common.TimedEvent;
import Clicky.Common.TimerType;
import Clicky.Common.ValidationUtils;
import Clicky.Exceptions.ValidationException;

/**
 * Concrete game session scaffold based on the old GameRoom lifecycle.
 *
 * Commands currently supported from clients:
 * - /ready
 */
public class GameServer extends BaseGameServer {

    private static final int READY_SECONDS = 30;
    private static final int ROUND_SECONDS = 30;
    private static final int TURN_SECONDS = 20;
    private static final int EVALUATION_SECONDS = 5;
    private static final String GAME_TAG = "[Game] ";

    private volatile Phase phase = Phase.INACTIVE;
    private volatile TimedEvent readyTimer;
    private volatile TimedEvent roundTimer;
    private volatile TimedEvent turnTimer;
    private volatile TimedEvent evaluationTimer;
    private volatile Long currentTurnPlayerId;
    private int roundNumber = 0;

    // start region for lifecycle hook implementations
    @Override
    protected void onSpectatorJoined(ServerThread client) {
        if (client == null) {
            return;
        }
        if (phase == Phase.INACTIVE) {
            return; // nothing to sync if the session isn't active
        }
        LoggerUtil.INSTANCE.info("[GameServer] Spectator joined: " + client.getDisplayName());
        unicastGameMessage(client, "Joined as spectator. Current active players: " + getActivePlayerCount());
        unicastGameStateToJoiner(client);
    }

    @Override
    protected void onPlayerJoined(ServerThread client) {
        if (client == null) {
            return;
        }
        if (phase == Phase.INACTIVE) {
            return; // nothing to sync if the session isn't active
        }
        LoggerUtil.INSTANCE.info("[GameServer] Player joined via ready: " + client.getDisplayName());
        unicastGameMessage(client, "Joined as active player. Waiting room status: "
                + getActivePlayerCount() + "/" + Constants.REQUIRE_PLAYERS + " ready.");
        if (phase != Phase.READY) {
            unicastGameStateToJoiner(client);
        }
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
        if (phase != Phase.INACTIVE && getActivePlayerCount() < Constants.REQUIRE_PLAYERS) {
            broadcastGameMessage("Not enough active players to continue.");
            onSessionEnd();
            return;
        }
    }

    @Override
    protected synchronized void onSessionStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onSessionStart() start");
        resetReadyTimer();
        roundNumber = 0;
        broadcastGameMessage("Session started.");
        LoggerUtil.INSTANCE.info("[GameServer] onSessionStart() end");
        onRoundStart();
    }

    @Override
    protected synchronized void onRoundStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() start");
        resetRoundTimer();
        startRoundTimer();
        phase = Phase.IN_PROGRESS; // toggle from READY or EVALUATION
        broadcastCurrentPhase();
        for (ServerThread player : getActivePlayers()) {
            // legacy since turns aren't recorded
            player.setTurnTaken(false);
            broadcastTurnStatus(player.getClientId(), false);
            // reset clicks for the round
            player.setClicks(0);
            broadcastCurrentClicks(player);// tell clients to reset?
        }
        roundNumber++; // TODO: future lessons may sync this as number later for better UI visibility
        broadcastGameMessage("Round " + roundNumber + " started. You have " + ROUND_SECONDS + "s total.");
        // example round setup

        broadcastGameMessage("A new round has started, use /click to click!");

        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() end");
        // onTurnStart(); this example doesn't use turns, all players take their
        // turn simultaneously within the round time limit
    }

    @Override
    protected synchronized void onTurnStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onTurnStart() start");
        resetTurnTimer();

        if (getActivePlayers().isEmpty()) {
            onSessionEnd();
            return;
        }

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

        // if all players have taken their turn, enter onRoundEnd() early instead of
        // waiting for the turn timer to expire
        boolean allTaken = getActivePlayers().stream().allMatch(ServerThread::isTurnTaken);
        if (allTaken) {
            // NOTE: be careful to not have two closely timed flows both call onRoundEnd()
            // simultaneously
            onRoundEnd();
        }
    }

    @Override
    protected synchronized void onRoundEnd() {
        LoggerUtil.INSTANCE.info("[GameServer] onRoundEnd() start");
        // prevent potential multiple calls to onRoundEnd() from both turn timer
        // expiring and all players taking their turn
        if (phase == Phase.EVALUATION) {
            LoggerUtil.INSTANCE.info("[GameServer] Already in evaluation phase, skipping redundant onRoundEnd() call");
            return;
        }
        phase = Phase.EVALUATION;
        broadcastCurrentPhase();
        resetRoundTimer();
        broadcastGameMessage("Round ended.");

        // example process round end logic; everyone gains a point for a correct guess
        broadcastGameMessage("Evaluating clicks for current round...");
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        Collections.sort(snapshot, (a, b) -> {
            return Integer.compare(b.getClicks(), a.getClicks()); // sort descending by clicks
        });
        int maxPoints = 10;
        StringBuilder sb = new StringBuilder();
        sb.append("Current Round Scoreboard \n");
        int i = 0;
        for (ServerThread player : snapshot) {
            sb.append(String.format("%s - Clicks:%d\n",
                    player.getDisplayName(),
                    player.getClicks()));
            // reward points with diminishing returns based on click rank for the round;
            // ties will receive the same points
            player.setPoints(player.getPoints() + Math.max(1, maxPoints - i));
            i++;
            broadcastPlayerPoints(player);
            // broadcastCurrentClicks(player); // NOTE: this is done at end of "turn"
            // action, no need to resync
        }
        broadcastGameMessage(sb.toString());
        LoggerUtil.INSTANCE.info("[GameServer] onRoundEnd() end");

        // logic to determine if session should end or next round should
        if (roundNumber >= 5) { // arbitrary end condition for example purposes
            onSessionEnd();
        } else {
            onRoundStart();
        }
    }

    @Override
    protected synchronized void onSessionEnd() {
        LoggerUtil.INSTANCE.info("[GameServer] onSessionEnd() start");
        resetReadyTimer();
        resetEvaluationTimer();
        resetTurnTimer();
        resetRoundTimer();

        currentTurnPlayerId = null;

        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());

        // if not a valid session, reset state and don't produce scoring/winner output
        if (phase.ordinal() <= Phase.READY.ordinal()) {
            doSessionReset(snapshot);
            return;
        }

        if (snapshot.isEmpty()) {
            broadcastGameMessage("Session ended with no winner.");
            doSessionReset(snapshot);
            return;
        }

        int topScore = snapshot.stream().mapToInt(ServerThread::getTotalClicks).max().orElse(0);
        List<ServerThread> winners = snapshot.stream()
                .filter(player -> player.getTotalClicks() == topScore)
                .toList();

        if (winners.size() == 1) {
            ServerThread winner = winners.get(0);
            broadcastGameMessage(String.format("Session ended: %s wins with %d clicks!",
                    winner.getDisplayName(),
                    winner.getTotalClicks()));
        } else {
            String winnerNames = winners.stream()
                    .map(ServerThread::getDisplayName)
                    .collect(Collectors.joining(", "));
            broadcastGameMessage(String.format("Session ended in a tie at %d clicks: %s",
                    topScore,
                    winnerNames));
        }

        phase = Phase.EVALUATION;
        broadcastCurrentPhase();
        broadcastGameMessage("Results displayed for " + EVALUATION_SECONDS + " seconds...");
        startEvaluationTimer(snapshot);
        LoggerUtil.INSTANCE.info("[GameServer] onSessionEnd() end — evaluation timer started");
    }

    // wrapped reset logic so a delay could be used to give users time to see the
    // end results before reset
    private void doSessionReset(List<ServerThread> snapshot) {
        LoggerUtil.INSTANCE.info("[GameServer] doSessionReset() start");
        resetEvaluationTimer();
        phase = Phase.INACTIVE;
        // reset player data and sync changes to clients before clearing active players,
        // so that clients have a chance to update any relevant UI (like ready status)
        // before being removed from the session
        for (ServerThread player : snapshot) {
            player.resetGameState();
        }
        // default client id is used as a reset trigger, no need to individually sync
        // resets for each property
        broadcastReadyStatus(Constants.DEFAULT_CLIENT_ID, false);
        clearActivePlayers();

        broadcastCurrentPhase();
        broadcastGameMessage("Session ended. Type /ready to join the next session.");
        LoggerUtil.INSTANCE.info("[GameServer] doSessionReset() end");
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
            readyTimer.setTickCallback(time -> {
                int clampedTime = Math.max(0, time);
                LoggerUtil.INSTANCE.info("[GameServer] Ready timer: " + clampedTime);
                broadcastGameTimer(TimerType.READY, clampedTime);
            });
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
        roundTimer.setTickCallback(time -> {
            int clampedTime = Math.max(0, time);
            LoggerUtil.INSTANCE.info("[GameServer] Round timer: " + clampedTime);
            broadcastGameTimer(TimerType.ROUND, clampedTime);
        });
    }

    private synchronized void resetRoundTimer() {
        if (roundTimer != null) {
            roundTimer.cancel();
            roundTimer = null;
        }
    }

    private synchronized void startTurnTimer() {
        turnTimer = new TimedEvent(TURN_SECONDS, this::onTurnEnd);
        turnTimer.setTickCallback(time -> {
            int clampedTime = Math.max(0, time);
            LoggerUtil.INSTANCE.info("[GameServer] Turn timer: " + clampedTime);
            broadcastGameTimer(TimerType.TURN, clampedTime);
        });
    }

    private synchronized void resetTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
        }
    }

    private synchronized void startEvaluationTimer(List<ServerThread> snapshot) {
        resetEvaluationTimer();
        evaluationTimer = new TimedEvent(EVALUATION_SECONDS, () -> doSessionReset(snapshot));
        evaluationTimer.setTickCallback(time -> {
            int clampedTime = Math.max(0, time);
            LoggerUtil.INSTANCE.info("[GameServer] Evaluation timer: " + clampedTime);
            broadcastGameTimer(TimerType.EVALUATION, clampedTime);
        });
    }

    private synchronized void resetEvaluationTimer() {
        if (evaluationTimer != null) {
            evaluationTimer.cancel();
            evaluationTimer = null;
        }
    }

    // End region for timer handlers ===================================
    private synchronized void checkReadyStatus() {
        if (phase != Phase.READY) {
            return;
        }
        if (getActivePlayerCount() >= Constants.REQUIRE_PLAYERS) {
            onSessionStart();
        } else {
            broadcastGameMessage("Ready check expired: not enough ready players.");
            onSessionEnd();
        }
    }

    // start region for handle*() methods called by Server
    protected void handleClick(ServerThread sender) {
        try {
            ValidationUtils.requireParticipating(isActivePlayer(sender));
            ValidationUtils.requirePhase(phase, Phase.IN_PROGRESS);
            ValidationUtils.requireNotAway(sender.isAway());
            // increment click on user
            sender.incrementClicks();
            broadcastCurrentClicks(sender);

        } catch (ValidationException e) {
            LoggerUtil.INSTANCE.warning("[GameServer] " + e.getMessage());
            unicastGameMessage(sender, e.getMessage());
        }
    }

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

    public void handleAwayToggle(ServerThread sender) {
        try {
            ValidationUtils.requireParticipating(isActivePlayer(sender),
                    sender.getDisplayName() + " is not a participant");
            boolean away = !sender.isAway();
            sender.setAway(away);
            broadcastAwayStatus(sender.getClientId(), away);

            if (away) {
                broadcastGameMessage(sender.getDisplayName() + " is away.");
                // If the away player is currently taking a turn, advance to next player
                if (currentTurnPlayerId != null && currentTurnPlayerId == sender.getClientId()) {
                    broadcastGameMessage(sender.getDisplayName() + " went away during their turn. Advancing turn.");
                    onTurnEnd();
                }
            } else {
                broadcastGameMessage(sender.getDisplayName() + " is back.");
            }
        } catch (ValidationException e) {
            LoggerUtil.INSTANCE.warning("[GameServer] " + e.getMessage());
            unicastGameMessage(sender, e.getMessage());
        }
    }

    // end region for handle*() methods called by Server

    // start region for helper methods to send data to clients

    private void broadcastPointsReset() { // optional reset for specific property, but we'll leverage the READY reset as
                                          // a full reset for simplicity in this example
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendPlayerPoints(Constants.DEFAULT_CLIENT_ID, 0));
    }

    private void broadcastPlayerPoints(ServerThread player) {
        if (player == null) {
            return;
        }
        Server.INSTANCE.sendOrDisconnect(
                serverThread -> serverThread.sendPlayerPoints(player.getClientId(), player.getPoints()));
    }

    private void unicastPlayerPoints(ServerThread target, long clientId, int points) {
        if (target == null) {
            return;
        }
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendPlayerPoints(clientId, points));
    }

    /**
     * Sends the current phase plus all existing active players' ready, turn, and
     * points state to a newly joined player.
     */
    private void unicastGameStateToJoiner(ServerThread joiner) {
        if (joiner == null) {
            return;
        }
        unicastCurrentPhase(joiner);
        // this scenario is likely impossible
        // but it's an example if we were to allow rejoining
        // if we didn't reset their data on leaving
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        for (ServerThread player : snapshot) {
            if (player.getClientId() == joiner.getClientId()) {
                continue;
            }
            unicastReadyStatus(joiner, player.getClientId(), player.isReady());
            unicastTurnStatus(joiner, player.getClientId(), player.isTurnTaken());
            unicastAwayStatus(joiner, player.getClientId(), player.isAway());
            unicastPlayerPoints(joiner, player.getClientId(), player.getPoints());
            unicastCurrentClicks(joiner, player.getClientId(), player.getClicks());
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

    private void unicastCurrentClicks(ServerThread target, long clientId, int clicks) {
        if (target == null) {
            return;
        }
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendClickCount(clientId, clicks));
    }

    private void broadcastCurrentClicks(ServerThread player) {
        if (player == null) {
            return;
        }
        Server.INSTANCE.sendOrDisconnect(
                serverThread -> serverThread.sendClickCount(player.getClientId(), player.getClicks()));
    }

    /** Notifies all connected clients of a player's ready status. */
    private void broadcastReadyStatus(long clientId, boolean isReady) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendReadyStatus(clientId, isReady));
    }

    /** Sends a player's ready status to a single client. */
    private void unicastReadyStatus(ServerThread target, long clientId, boolean isReady) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendReadyStatus(clientId, isReady));
    }

    /** Notifies all connected clients of a player's away status. */
    private void broadcastAwayStatus(long clientId, boolean isAway) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendAwayStatus(clientId, isAway));
    }

    /** Sends a player's away status to a single client. */
    private void unicastAwayStatus(ServerThread target, long clientId, boolean isAway) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendAwayStatus(clientId, isAway));
    }

    /** Notifies all connected clients of a player's turn-taken status. */
    private void broadcastTurnStatus(long clientId, boolean hasTakenTurn) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendTurnStatus(clientId, hasTakenTurn));
    }

    /** Sends a player's turn-taken status to a single client. */
    private void unicastTurnStatus(ServerThread target, long clientId, boolean hasTakenTurn) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendTurnStatus(clientId, hasTakenTurn));
    }

    /**
     * Sends a game event message to all connected clients.
     * Tagged with GAME_CLIENT_ID so clients route it to the game events panel.
     */
    private void broadcastGameMessage(String message) {
        final String formatted = GAME_TAG + message;
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendGameMessage(formatted));
    }

    /**
     * Sends a game event message to a single client.
     * Tagged with GAME_CLIENT_ID so the client routes it to the game events panel.
     */
    private void unicastGameMessage(ServerThread target, String message) {
        if (target == null) {
            return;
        }
        final String formatted = GAME_TAG + message;
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendGameMessage(formatted));
    }

    private void broadcastGameTimer(TimerType timerType, int secondsRemaining) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendGameTimer(timerType, secondsRemaining));
    }

    // end region for helper methods to send data to clients
}
