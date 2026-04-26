package Project.Server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import Project.Common.Card;
import Project.Common.Constants;
import Project.Common.Grid;
import Project.Common.LoggerUtil;
import Project.Common.Phase;
import Project.Common.TimedEvent;
import Project.Common.TimerType;
import Project.Common.ValidationUtils;
import Project.Exceptions.ValidationException;

/**
 * Concrete game session scaffold based on the old GameRoom lifecycle.
 *
 * Commands currently supported from clients:
 * - /ready
 * - /card <cardId> <x> <y>
 */
public class GameServer extends BaseGameServer {

    private static final int READY_SECONDS = 30;
    private static final int ROUND_SECONDS = 30;
    private static final int TURN_SECONDS = 20;
    private static final int EVALUATION_SECONDS = 5;
    private static final int TURN_TRANSITION_SECONDS = 3; // brief pause after a turn ends so clients can see grid
                                                          // changes
    private static final String GAME_TAG = "[Game] ";
    private static final int GRID_WIDTH = 5;
    private static final int GRID_HEIGHT = 5;
    private static final int HAND_SIZE = 3;

    private volatile Phase phase = Phase.INACTIVE;
    private volatile TimedEvent readyTimer;
    private volatile TimedEvent roundTimer;
    private volatile TimedEvent turnTimer;
    private volatile TimedEvent evaluationTimer;
    private volatile TimedEvent turnTransitionTimer;
    private volatile Long currentTurnPlayerId;
    private int roundNumber = 0;
    private volatile Grid currentGrid;
    private volatile long currentGridSeed = 0L;
    private final Random deckRng = new Random();
    private volatile Deck currentDeck;

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
        client.clearCardIds();
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
        // If the player who just left was holding the current turn, advance immediately
        // rather than waiting for the turn timer to expire.
        if (phase != Phase.INACTIVE
                && currentTurnPlayerId != null
                && currentTurnPlayerId == client.getClientId()) {
            broadcastGameMessage(client.getDisplayName() + " left during their turn. Advancing turn.");
            onTurnEnd();
        }
    }

    @Override
    protected synchronized void onSessionStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onSessionStart() start");
        resetReadyTimer();
        roundNumber = 0;
        currentTurnPlayerId = null;

        clearCurrentGrid();
        for (ServerThread player : getActivePlayers()) {
            player.clearCardIds();
        }
        currentDeck = new Deck();
        currentDeck.loadCardsFromFile();

        currentGridSeed = System.currentTimeMillis();
        currentGrid = new Grid();
        currentGrid.setSize(GRID_WIDTH, GRID_HEIGHT);
        currentGrid.setToRandom(currentGridSeed);
        LoggerUtil.INSTANCE.info("[GameServer] Current grid:\n" + currentGrid.toGridString());

        broadcastCardCatalog();
        broadcastGridSeed();

        broadcastGameMessage("Session started.");
        LoggerUtil.INSTANCE.info("[GameServer] onSessionStart() end");
        onRoundStart();
    }

    @Override
    protected synchronized void onRoundStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() start");
        resetRoundTimer();
        // startRoundTimer(); // Round timer generally isn't useful during individual
        // turns (unless you do something like <Num Players> * <Turn Duration>)
        phase = Phase.IN_PROGRESS; // toggle from READY or EVALUATION
        broadcastCurrentPhase();
        for (ServerThread player : getActivePlayers()) {
            player.setTurnTaken(false);
            broadcastTurnStatus(player.getClientId(), false);
            drawUpToHandSize(player);
            unicastHand(player);
        }
        roundNumber++; // TODO: future lessons may sync this as number later for better UI visibility
        broadcastGameMessage("Round " + roundNumber + " started. Each player gets one grid action.");
        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() end");
        onTurnStart(); // this example users onTurnStart() for individual turn pacing
    }

    @Override
    protected synchronized void onTurnStart() {
        LoggerUtil.INSTANCE.info("[GameServer] onTurnStart() start");
        resetTurnTimer();

        if (getActivePlayers().isEmpty()) {
            onSessionEnd();
            return;
        }

        ServerThread currentPlayer = findNextTurnPlayer();

        if (currentPlayer == null) {
            // If no non-away players are eligible, end the round to avoid stalling turn
            // progression.
            LoggerUtil.INSTANCE.info("[GameServer] No eligible non-away player found for turn start. Ending round.");
            onRoundEnd();
            return;
        }

        currentTurnPlayerId = currentPlayer.getClientId();
        drawUpToHandSize(currentPlayer);
        unicastHand(currentPlayer);
        startTurnTimer();
        broadcastCurrentTurn(); // Message is generated on the client-side based on the clientId
        LoggerUtil.INSTANCE.info("[GameServer] onTurnStart() end");
    }

    @Override
    protected synchronized void onTurnEnd() {
        LoggerUtil.INSTANCE.info("[GameServer] onTurnEnd() start");
        resetTurnTimer();
        // if the current player didn't take their turn, mark it as complete and sync
        // this prevents infinite turn loops
        if (currentTurnPlayerId != null) {
            ServerThread currentPlayer = activePlayers.get(currentTurnPlayerId);
            if (currentPlayer != null && !currentPlayer.isTurnTaken()) {
                currentPlayer.setTurnTaken(true);
                broadcastTurnStatus(currentPlayer.getClientId(), true);
                broadcastGameMessage(currentPlayer.getDisplayName() + " ran out of time and missed their turn.");
            }
        }
        LoggerUtil.INSTANCE.info("[GameServer] onTurnEnd() end");

        // if all players have taken their turn, enter onRoundEnd() early instead of
        // waiting for the turn timer to expire
        final boolean allTaken = haveAllNonAwayPlayersTakenTurn();
        if (turnTransitionTimer != null) {
            turnTransitionTimer.cancel();
        }
        turnTransitionTimer = new TimedEvent(TURN_TRANSITION_SECONDS, () -> {
            if (allTaken) {
                // NOTE: be careful to not have two closely timed flows both call onRoundEnd()
                // simultaneously
                onRoundEnd();
            } else {
                // Start the next turn after the same transition delay.
                onTurnStart();
            }
        });

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

        LoggerUtil.INSTANCE.info("[GameServer] onRoundEnd() end");

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
        broadcastGridReset();
        clearCurrentGrid();

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

        int topScore = snapshot.stream().mapToInt(ServerThread::getPoints).max().orElse(0);
        List<ServerThread> winners = snapshot.stream()
                .filter(player -> player.getPoints() == topScore)
                .toList();

        if (winners.size() == 1) {
            ServerThread winner = winners.get(0);
            broadcastGameMessage(String.format("Session ended: %s wins with %d points!",
                    winner.getDisplayName(),
                    winner.getPoints()));
        } else {
            String winnerNames = winners.stream()
                    .map(ServerThread::getDisplayName)
                    .collect(Collectors.joining(", "));
            broadcastGameMessage(String.format("Session ended in a tie at %d points: %s",
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

    public void handleCardAction(ServerThread sender, int cardId, int x, int y) {
        try {
            ValidationUtils.requireParticipating(isActivePlayer(sender));
            ValidationUtils.requireNotAway(sender.isAway());
            ValidationUtils.requirePhase(phase, Phase.IN_PROGRESS);
            ValidationUtils.requireTurnNotTaken(sender.isTurnTaken());
            ValidationUtils.requireCurrentPlayer(currentTurnPlayerId, sender.getClientId());
            ValidationUtils.requireNonNull(currentGrid, "Grid is not initialized yet.");
            ValidationUtils.requireValidCardId(cardId);
            ValidationUtils.requireInBounds(x, y, currentGrid.getWidth(), currentGrid.getHeight());
            ValidationUtils.requireNonNull(currentDeck, "Deck is not initialized yet.");

            List<Integer> hand = sender.getCardIds();
            ValidationUtils.requireCardInHand(hand, cardId);
            Card selectedCard = currentDeck.getById(cardId);
            ValidationUtils.requireNonNull(selectedCard, "Card data was not found in the deck.");
            int value = selectedCard.getMod();

            currentGrid.applyModifier(value, x, y);
            LoggerUtil.INSTANCE.info("[GameServer] Grid after update:\n" + currentGrid.toGridString());
            broadcastAffectedGridCells(x, y);

            // determine score based on odds; add and broadcast
            int oddCount = currentGrid.countOdd(x, y);
            int pointsGained = oddCount; // example scoring: 1 point per odd cell
            if (pointsGained > 0) {
                sender.setPoints(sender.getPoints() + pointsGained);
                broadcastPlayerPoints(sender);
            }

            sender.removeCardId(cardId);
            unicastHand(sender);
            String actionSummary = String.format(
                    "%s played card %d (%+d) at (%d,%d). Plus-sign odd total: %d => +%d point(s).",
                    sender.getDisplayName(),
                    cardId,
                    value,
                    x,
                    y,
                    oddCount,
                    pointsGained);
            broadcastGameMessage(actionSummary);
            LoggerUtil.INSTANCE.info("[GameServer] " + actionSummary);

            sender.setTurnTaken(true);
            broadcastTurnStatus(sender.getClientId(), true);
            onTurnEnd();
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

    private void unicastHand(ServerThread target) {
        List<Integer> snapshot = new ArrayList<>(target.getCardIds());
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendCardHand(target.getClientId(), snapshot));
    }

    private void broadcastCardCatalog() {
        if (currentDeck == null) {
            return;
        }
        List<Card> catalog = currentDeck.getCatalogSnapshot();
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendCardCatalog(catalog));
    }

    private void unicastCardCatalog(ServerThread target) {
        if (currentDeck == null) {
            return;
        }
        List<Card> catalog = currentDeck.getCatalogSnapshot();
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendCardCatalog(catalog));
    }

    /** Sends the current turn owner to a single client when applicable. */
    private void unicastCurrentPlayer(ServerThread target) {
        if (target == null || currentTurnPlayerId == null) {
            return;
        }
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendCurrentTurn(currentTurnPlayerId));
    }

    /** Sends the current turn owner to all connected clients when applicable. */
    private void broadcastCurrentTurn() {
        if (currentTurnPlayerId == null) {
            return;
        }
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendCurrentTurn(currentTurnPlayerId));
    }

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
        unicastCurrentPlayer(joiner);
        if (currentDeck != null) {
            unicastCardCatalog(joiner);
        }
        // this scenario is likely impossible
        // but it's an example if we were to allow rejoining
        // if we didn't reset their data on leaving
        if (isActivePlayer(joiner)) {
            unicastHand(joiner);
        }
        if (currentGrid != null) {
            unicastGridSeed(joiner);
            unicastGridState(joiner);
        }
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        for (ServerThread player : snapshot) {
            if (player.getClientId() == joiner.getClientId()) {
                continue;
            }
            unicastReadyStatus(joiner, player.getClientId(), player.isReady());
            unicastTurnStatus(joiner, player.getClientId(), player.isTurnTaken());
            unicastAwayStatus(joiner, player.getClientId(), player.isAway());
            unicastPlayerPoints(joiner, player.getClientId(), player.getPoints());
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

    private void broadcastGridSeed() {
        if (currentGrid == null) {
            LoggerUtil.INSTANCE.warning("[GameServer] Tried to broadcast grid seed with no active grid.");
            return;
        }
        final int width = currentGrid.getWidth();
        final int height = currentGrid.getHeight();
        final long seed = currentGridSeed;
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendGridSeed(seed, width, height));
    }

    private void unicastGridSeed(ServerThread target) {
        if (currentGrid == null) {
            LoggerUtil.INSTANCE.warning("[GameServer] Tried to unicast grid seed with no active grid.");
            return;
        }
        Server.INSTANCE.unicast(target,
                serverThread -> serverThread.sendGridSeed(currentGridSeed, currentGrid.getWidth(),
                        currentGrid.getHeight()));
    }

    private void broadcastGridReset() {
        LoggerUtil.INSTANCE.info("[GameServer] Broadcasting grid reset trigger.");
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendGridSeed(0L, 0, 0));
    }

    private void broadcastGridCell(int x, int y, int value) {
        Server.INSTANCE.sendOrDisconnect(serverThread -> serverThread.sendGridCell(x, y, value));
    }

    private void unicastGridCell(ServerThread target, int x, int y, int value) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendGridCell(x, y, value));
    }

    private void unicastGridState(ServerThread target) {
        if (currentGrid == null) {
            LoggerUtil.INSTANCE.warning("[GameServer] Tried to unicast grid state with no active grid.");
            return;
        }
        for (int x = 0; x < currentGrid.getWidth(); x++) {
            for (int y = 0; y < currentGrid.getHeight(); y++) {
                unicastGridCell(target, x, y, currentGrid.getValue(x, y));
            }
        }
    }

    private void broadcastAffectedGridCells(int x, int y) {
        if (currentGrid == null) {
            return;
        }
        int w = currentGrid.getWidth();
        int h = currentGrid.getHeight();
        // center
        if (ValidationUtils.isInBounds(x, y, w, h)) {
            broadcastGridCell(x, y, currentGrid.getValue(x, y));
        }
        // top
        if (ValidationUtils.isInBounds(x, y - 1, w, h)) {
            broadcastGridCell(x, y - 1, currentGrid.getValue(x, y - 1));
        }
        // bottom
        if (ValidationUtils.isInBounds(x, y + 1, w, h)) {
            broadcastGridCell(x, y + 1, currentGrid.getValue(x, y + 1));
        }
        // left
        if (ValidationUtils.isInBounds(x - 1, y, w, h)) {
            broadcastGridCell(x - 1, y, currentGrid.getValue(x - 1, y));
        }
        // right
        if (ValidationUtils.isInBounds(x + 1, y, w, h)) {
            broadcastGridCell(x + 1, y, currentGrid.getValue(x + 1, y));
        }
    }

    private void clearCurrentGrid() {
        if (currentGrid != null) {
            currentGrid.clear();
            currentGrid = null;
        }
        currentGridSeed = 0L;
    }

    // end region for helper methods to send data to clients

    // start region misc utility methods

    // Picks the next eligible turn owner in deterministic client-id order.
    private ServerThread findNextTurnPlayer() {
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        snapshot.sort(Comparator.comparingLong(ServerThread::getClientId));
        return snapshot.stream()
                .filter(player -> !player.isAway() && !player.isTurnTaken())
                .findFirst()
                .orElse(null);
    }

    private boolean haveAllNonAwayPlayersTakenTurn() {
        return getActivePlayers().stream()
                .filter(player -> !player.isAway())
                .allMatch(ServerThread::isTurnTaken);
    }

    private void drawUpToHandSize(ServerThread player) {
        if (currentDeck == null) {
            return;
        }
        while (player.getCardIds().size() < HAND_SIZE) {
            int nextCardId = currentDeck.drawCardId(deckRng);
            if (nextCardId <= 0) {
                break;
            }
            player.addCardId(nextCardId);
        }
    }
    // end region misc utility methods
}
