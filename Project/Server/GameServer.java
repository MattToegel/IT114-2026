package Project.Server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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
    private int roundNumber = 0;
    // example data
    private int hiddenNumber = 0;

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
                + getActivePlayerCount() + "/" + MIN_PLAYERS_TO_START + " ready.");
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
        if (phase != Phase.INACTIVE && getActivePlayerCount() < MIN_PLAYERS_TO_START) {
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
        }
        roundNumber++; // TODO: future lessons may sync this as number later for better UI visibility
        broadcastGameMessage("Round " + roundNumber + " started. You have " + ROUND_SECONDS + "s total.");
        // example round setup
        hiddenNumber = new Random().nextInt(10) + 1;
        broadcastGameMessage("A random number between 1-10 has been chosen, use /guess <value> to guess.");
        LoggerUtil.INSTANCE.info("[GameServer] onRoundStart() end");
        onTurnStart(); // this example users onTurnStart() for individual turn pacing
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

        snapshot.sort(Comparator.comparingLong(ServerThread::getClientId));

        // Find the first player in sorted order who hasn't taken their turn yet.
        ServerThread currentPlayer = snapshot.stream()
                .filter(p -> !p.isTurnTaken())
                .findFirst()
                .orElse(null);

        if (currentPlayer == null) {
            // This shouldn't happen: onTurnEnd() guards the allTookTurn check before
            // calling onTurnStart(), so there should always be a player waiting.
            LoggerUtil.INSTANCE.severe("[GameServer] onTurnStart() called but no player without a turn was found.");
            return;
        }

        currentTurnPlayerId = currentPlayer.getClientId();
        startTurnTimer();
        broadcastCurrentTurn(); // Message is generated on the client-side based on the clientId
        // Potentially excessive, notes whose turn it is and tells how to take a turn
        broadcastGameMessage("Turn started for " + currentPlayer.getDisplayName() + ". Use /guess <value> within "
                + TURN_SECONDS + "s.");
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
        // onRoundEnd(); this example doesn't use turns, but this hook is called at the
        // end of handleTurn() and we don't want it to end the round

        // if all players have taken their turn, enter onRoundEnd() early instead of
        // waiting for the turn timer to expire
        boolean allTaken = getActivePlayers().stream().allMatch(ServerThread::isTurnTaken);
        if (allTaken) {
            // NOTE: be careful to not have two closely timed flows both call onRoundEnd()
            // simultaneously
            onRoundEnd();
        } else {
            onTurnStart(); // start next turn immediately after previous turn ends
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
        broadcastGameMessage("Evaluating guesses... The correct number was " + hiddenNumber);
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        for (ServerThread player : snapshot) {
            if (player.getGuess() == hiddenNumber) {
                player.setPoints(player.getPoints() + 1);
                // sync points to all
                broadcastPlayerPoints(player);
                // feedback
                broadcastGameMessage(
                        String.format("%s guessed correctly and gained a point!", player.getDisplayName()));
                // can reset guess here
                player.setGuess(0);
            } else {
                unicastGameMessage(player, "Your guess was incorrect.");
            }
        }

        LoggerUtil.INSTANCE.info("[GameServer] onRoundEnd() end");

        if (roundNumber >= 5) { // arbitrary end condition for example purposes
            onSessionEnd();
        } else {
            onRoundStart();
        }
        // onSessionEnd();
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
        // find user with highest score; they're the winner (uses stream api)
        snapshot.stream().max((p1, p2) -> Integer.compare(p1.getPoints(), p2.getPoints())).ifPresentOrElse(winner -> {
            broadcastGameMessage(String.format("Session ended: %s wins with %d points!", winner.getDisplayName(),
                    winner.getPoints()));
        }, () -> {
            broadcastGameMessage("Session ended with no winner.");
        });

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

    protected void handleGuess(ServerThread sender, String guess) {
        try {
            ValidationUtils.requireParticipating(isActivePlayer(sender));
            ValidationUtils.requirePhase(phase, Phase.IN_PROGRESS);
            ValidationUtils.requireTurnNotTaken(sender.isTurnTaken()); // optional check to prevent multiple guesses if
                                                                       // you want to enforce one guess per turn; can be
                                                                       // removed and adjusted for more flexible rules
                                                                       // (would need to decide how to handle multiple
                                                                       // guesses in the game logic, like take the first
                                                                       // guess, average them, etc.)
            ValidationUtils.requireCurrentPlayer(currentTurnPlayerId, sender.getClientId()); // Key validation to ensure
                                                                                             // individual turn order is
                                                                                             // enforced
            guess = ValidationUtils.requireValidTurnOption(guess.trim());
            // although validation should verify it's a number, I'll see do a try/catch just
            // in case
            // that way if I mistakenly change requireValidTurnOption() in the future and it
            // stops validating properly, I have a fallback to prevent server crashes from
            // NumberFormatException
            try {
                int guessValue = Integer.parseInt(guess);
                // record server local state (used in round end)
                sender.setGuess(guessValue);
                // unicast guess to player for confirmation
                unicastGuessConfirmation(sender, guessValue);
                // NOTE: we won't evaluate here, we'll do it during onRoundEnd()
            } catch (NumberFormatException e) {
                LoggerUtil.INSTANCE.warning("[GameServer] Failed to parse turn action as number: " + guess);
                unicastGameMessage(sender,
                        "Failed to parse your guess as a number. Please enter a valid number between 1 and 10.");
                return;
            }

            // keep the guess hidden from other players in this example
            broadcastGameMessage(sender.getDisplayName() + " made a guess.");
            // Note: technically if your action has data, turnTaken can be derived by
            // whether or not data was recorded, but I'll keep it as a separate property for
            // simplicity and flexibility. In a fuller project, deriving information is more
            // efficient
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

    /**
     * Handles a player's turn action. Validates the action, records the turn, and
     * advances the game. Sends an error message back to the player on failure.
     */
    @Deprecated
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
            onTurnEnd();
        } catch (ValidationException e) {
            LoggerUtil.INSTANCE.warning("[GameServer] " + e.getMessage());
            unicastGameMessage(sender, e.getMessage());
        }
    }

    // end region for handle*() methods called by Server

    // start region for helper methods to send data to clients

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

    private void unicastGuessConfirmation(ServerThread target, int guess) {
        Server.INSTANCE.unicast(target, serverThread -> serverThread.sendGuessConfirmation(guess));
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
        List<ServerThread> snapshot = new ArrayList<>(getActivePlayers());
        for (ServerThread player : snapshot) {
            if (player.getClientId() == joiner.getClientId()) {
                continue;
            }
            unicastReadyStatus(joiner, player.getClientId(), player.isReady());
            unicastTurnStatus(joiner, player.getClientId(), player.isTurnTaken());
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
