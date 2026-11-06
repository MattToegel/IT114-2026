package Project.Server;

import Project.Common.*;
import Project.Exceptions.IT114CustomException;
import Project.Exceptions.SessionNotInProgressException;
import Project.Exceptions.NotParticipantException;
import Project.Exceptions.AlreadySubmittedException;
import Project.Exceptions.InvalidGuessException;
import Project.Exceptions.NotYourTurnException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;

/**
 * Owns the small ready-check session. All methods run while holding Server's
 * monitor, including timer callbacks, so a request cannot race a deadline.
 */
final class GameServer {

    private static final long READY_MS = 30_000L;

    private static final long TURN_MS = 30_000L;

    private final Timer timer = new Timer(true);

    private final Map<Long, User> players = new LinkedHashMap<>();

    // TODO: Remove the random generator and secret when implementing your project game.
    private final Random guessRandom = new Random();

    // TODO: Remove this temporary guessing-game field when implementing your project game.
    private int secret;

    private Phase phase = Phase.INACTIVE;

    private TimerTask activeDeadline;

    private int roundNumber;

    private long currentTurnPlayerId;

    /**
     * Reject game actions outside an active session before they can change game state.
     * @throws SessionNotInProgressException when the session is not in progress
     */
    private void checkSessionInProgress() throws SessionNotInProgressException {
        if (phase != Phase.IN_PROGRESS) {
            throw new SessionNotInProgressException();
        }
    }

    /**
     * Reject spectators so only participants from the current ready check can act.
     * @param sender the requesting user
     * @throws NotParticipantException when the user is a spectator
     */
    private void checkIsParticipant(User sender) throws NotParticipantException {
        if (!players.containsKey(sender.getClientId())) {
            throw new NotParticipantException();
        }
    }

    /**
     * Reject repeated actions to enforce the current one-action-per-round rule.
     * @param sender the requesting participant
     * @throws AlreadySubmittedException when the participant already acted
     */
    private void checkNotSubmitted(User sender) throws AlreadySubmittedException {
        if (sender.isSubmitted()) {
            throw new AlreadySubmittedException();
        }
    }

    /**
     * Reject actions from other players to enforce the server's current turn.
     * @param sender the requesting participant
     * @throws NotYourTurnException when another participant owns the turn
     */
    private void checkTakeTurn(User sender) throws NotYourTurnException {
        if (sender.getClientId() != currentTurnPlayerId) {
            throw new NotYourTurnException();
        }
    }

    /**
     * Reject values outside 1 through 5 before the temporary game records a guess.
     * @param guess the requested whole number
     * @throws InvalidGuessException when the value is outside 1 through 5
     */
    private void checkGuess(int guess) throws InvalidGuessException {
        // TODO: Replace this temporary validation with your project's rules.
        if (guess < 1 || guess > 5) {
            throw new InvalidGuessException();
        }
    }

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
     * The current participant submits once; points are awarded when the round is evaluated.
     *
     * Failed checks send their specific explanation before any action is recorded.
     *
     * @param sender the user who entered /guess
     * @param guess the requested whole number from 1 through 5
     */
    void handleGuess(User sender, int guess) {
        // TODO: Remove this temporary handler when implementing your project game.
        try {
            checkSessionInProgress();
            checkIsParticipant(sender);
            checkNotSubmitted(sender);
            checkTakeTurn(sender);
            checkGuess(guess);

            sender.setSubmitted(true);
            sender.setGuess(guess);
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendGuessConfirmation(guess));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(sender.getClientId(), true));
            onTurnEnd();
        } catch (IT114CustomException exception) {
            Server.INSTANCE.sendTo(sender.getClientId(), recipient -> recipient.sendMessage(
                    Constants.DEFAULT_CLIENT_ID, exception.getMessage()));
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
                onTurnTimeout();
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

    /** Begin the session after readiness closes; clear prior points and open round one. */
    private void onSessionStart() {
        roundNumber = 0;
        for (User player : players.values()) {
            player.setPoints(0);
        }
        currentTurnPlayerId = 0;
        // TODO: Replace this temporary secret selection with your project's session setup.
        secret = guessRandom.nextInt(5) + 1;
        onRoundStart();
    }

    /** Update game state after Server removes a participant; advance or reset when needed.
     *
     * @param client the disconnected user
     */
    void onClientLeft(User client) {
        if (players.remove(client.getClientId()) == null) {
            return;
        }
        client.setGuess(0);
        client.setReady(false);
        client.setSubmitted(false);
        client.setPoints(0);
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(client.getClientId(), false));
        if (players.isEmpty()) {
            resetSession("Session ended because all players left");
        } else if (phase == Phase.IN_PROGRESS) {
            if (client.getClientId() == currentTurnPlayerId) {
                onTurnEnd();
            } else if (allGuessed()) {
                onRoundEnd();
            }
        }
    }

    /** Replay round and public state; only its owner receives a submitted guess.
     *
     * Uses the same ServerThread send methods as live updates to catch this one
     * client up with authoritative state. Sync selects current values and recipients.
     * Send methods bundle those values for transmission.
     *
     * @param recipientId the newly registered connection
     */
    void syncTo(long recipientId) {
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGamePhase(phase));
        if (roundNumber > 0) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendRound(roundNumber));
        }
        Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendCurrentTurn(currentTurnPlayerId));
        for (long id : players.keySet()) {
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendReadyStatus(id, true));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGuessStatus(id, players.get(id).isSubmitted()));
            Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendPoints(id, players.get(id).getPoints()));
            if (id == recipientId && players.get(id).isSubmitted()) {
                Server.INSTANCE.sendTo(recipientId, recipient -> recipient.sendGuessConfirmation(players.get(id).getGuess()));
            }
        }
    }

    /** Open an individual round while retaining the current secret and starting the first turn. */
    private void onRoundStart() {
        roundNumber++;
        for (Map.Entry<Long, User> entry : players.entrySet()) {
            entry.getValue().setSubmitted(false);
            entry.getValue().setGuess(0);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(entry.getKey(), false));
        }
        phase = Phase.IN_PROGRESS;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendRound(roundNumber));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Use /guess <value> with a number from 1 to 5"));
        onTurnStart();
    }

    /** Choose the lowest-ID participant who has not acted, then announce the owner and deadline once. */
    private void onTurnStart() {
        currentTurnPlayerId = players.entrySet().stream()
                .filter(entry -> !entry.getValue().isSubmitted())
                .mapToLong(Map.Entry::getKey)
                .min().orElse(0);
        if (currentTurnPlayerId == 0) {
            onRoundEnd();
            return;
        }
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(currentTurnPlayerId));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, players.get(currentTurnPlayerId).getDisplayName()
                + " has " + ((TURN_MS + 999) / 1000) + " seconds to /guess <value> from 1 to 5"));
        schedule(TURN_MS);
    }

    /** Invalidate the finished turn deadline, then select another player or evaluate the round. */
    private void onTurnEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        cancelDeadline();
        currentTurnPlayerId = 0;
        if (allGuessed()) {
            onRoundEnd();
        } else {
            onTurnStart();
        }
    }

    /** Mark the owner as acted without a guess or point, then advance the turn. */
    private void onTurnTimeout() {
        User player = players.get(currentTurnPlayerId);
        if (player != null) {
            player.setSubmitted(true);
            player.setGuess(0);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(currentTurnPlayerId, true));
        }
        onTurnEnd();
    }

    /** Evaluate each round once, retain an unsolved secret, and advance or finish the session. */
    private void onRoundEnd() {
        if (phase != Phase.IN_PROGRESS) {
            return;
        }
        cancelDeadline();
        currentTurnPlayerId = 0;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(0));
        phase = Phase.EVALUATION;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        boolean hasWinner = false;
        boolean guessedCorrectly = false;
        for (Map.Entry<Long, User> entry : players.entrySet()) {
            User player = entry.getValue();
            // TODO: Replace this exact-guess scoring rule with your project's scoring rules.
            if (player.isSubmitted() && player.getGuess() == secret) {
                player.setPoints(player.getPoints() + 1);
                guessedCorrectly = true;
            }
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(entry.getKey(), player.getPoints()));
            // TODO: Replace the temporary five-point winning condition for your project.
            if (player.getPoints() >= 5) {
                hasWinner = true;
            }
        }
        if (guessedCorrectly) {
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(
                    Constants.DEFAULT_CLIENT_ID, "The number was " + secret));
        } else {
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(
                    Constants.DEFAULT_CLIENT_ID, "No correct guesses. The secret stays the same"));
        }
        if (hasWinner || players.isEmpty()) {
            onSessionEnd();
        } else {
            if (guessedCorrectly) {
                // TODO: Replace this temporary secret selection with your project's game rules.
                secret = guessRandom.nextInt(5) + 1;
            }
            onRoundStart();
        }
    }

    /** Announce players who reached the score goal, including ties, before clearing the session. */
    private void onSessionEnd() {
        if (!players.isEmpty()) {
            int high = players.values().stream().mapToInt(player -> player.getPoints()).max().orElse(0);
            List<String> winners = new ArrayList<>();
            for (User player : players.values()) {
                if (player.getPoints() == high) {
                    winners.add(player.getDisplayName());
                }
            }
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, "Winners: " + String.join(", ", winners) + " with " + high + " points"));
        }
        resetSession("Example session finished; type /ready to repeat");
    }

    /** Invalidate old deadlines, clear round state, readiness and points, and broadcast the reset state.
     *
     * @param message the reason broadcast to all connected users
     */
    private void resetSession(String message) {
        cancelDeadline();
        for (User user : players.values()) {
            user.setReady(false);
            user.setSubmitted(false);
            user.setGuess(0);
            user.setPoints(0);
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendReadyStatus(user.getClientId(), false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendGuessStatus(user.getClientId(), false));
            Server.INSTANCE.sendGameAction(recipient -> recipient.sendPoints(user.getClientId(), 0));
        }
        players.clear();
        roundNumber = 0;
        secret = 0;
        currentTurnPlayerId = 0;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendCurrentTurn(0));
        phase = Phase.INACTIVE;
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendGamePhase(phase));
        Server.INSTANCE.sendGameAction(recipient -> recipient.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
    }

    /**
     * Check whether every remaining participant acted this round.
     *
     * @return true when every remaining participant has acted
     */
    private boolean allGuessed() {
        return !players.isEmpty() && players.values().stream().allMatch(player -> player.isSubmitted());
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
