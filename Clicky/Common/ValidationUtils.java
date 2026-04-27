package Clicky.Common;

import Clicky.Exceptions.AlreadyReadyException;
import Clicky.Exceptions.BlankValidationException;
import Clicky.Exceptions.ConditionValidationException;
import Clicky.Exceptions.DuplicateTurnChoiceException;
import Clicky.Exceptions.InvalidGamePhaseException;
import Clicky.Exceptions.InvalidTurnOptionException;
import Clicky.Exceptions.NullValidationException;
import Clicky.Exceptions.PlayerNotParticipatingException;

public final class ValidationUtils {

    private static final String DEFAULT_TRUE_MESSAGE = "That action isn't allowed right now.";
    private static final String DEFAULT_NULL_MESSAGE = "Some required data is missing. Please try again.";
    private static final String DEFAULT_BLANK_MESSAGE = "Please enter a value.";
    private static final String DEFAULT_PHASE_READY_MESSAGE = "You can only do that while the game is waiting for players.";
    private static final String DEFAULT_PHASE_IN_PROGRESS_MESSAGE = "You can only do that during an active round.";
    private static final String DEFAULT_PHASE_MESSAGE = "That action isn't available right now.";
    private static final String DEFAULT_PARTICIPATING_MESSAGE = "You need to be ready before you can do that.";
    private static final String DEFAULT_ALREADY_READY_MESSAGE = "You're already marked ready for this session.";
    private static final String DEFAULT_TURN_TAKEN_MESSAGE = "You already made your choice for this round.";
    private static final String DEFAULT_TURN_OPTION_MESSAGE = "That turn option is not available.";
    private static final String DEFAULT_AWAY_MESSAGE = "Player is away and cannot perform actions";

    private ValidationUtils() {
    }

    public static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    public static void requireTrue(boolean condition) throws ConditionValidationException {
        requireTrue(condition, DEFAULT_TRUE_MESSAGE);
    }

    public static void requireTrue(boolean condition, String errorMessage) throws ConditionValidationException {
        if (!condition) {
            throw new ConditionValidationException(errorMessage);
        }
    }

    public static <T> T requireNonNull(T value) throws NullValidationException {
        return requireNonNull(value, DEFAULT_NULL_MESSAGE);
    }

    public static <T> T requireNonNull(T value, String errorMessage) throws NullValidationException {
        if (value == null) {
            throw new NullValidationException(errorMessage);
        }
        return value;
    }

    public static String requireNotBlank(String value) throws BlankValidationException {
        return requireNotBlank(value, DEFAULT_BLANK_MESSAGE);
    }

    public static String requireNotBlank(String value, String errorMessage) throws BlankValidationException {
        if (value == null || value.isBlank()) {
            throw new BlankValidationException(errorMessage);
        }
        return value;
    }

    public static Phase requirePhase(Phase actual, Phase expected)
            throws InvalidGamePhaseException {
        String errorMessage = DEFAULT_PHASE_MESSAGE;
        if (expected == Phase.READY) {
            errorMessage = DEFAULT_PHASE_READY_MESSAGE;
        } else if (expected == Phase.IN_PROGRESS) {
            errorMessage = DEFAULT_PHASE_IN_PROGRESS_MESSAGE;
        }
        return requirePhase(actual, expected, errorMessage);
    }

    public static Phase requirePhase(Phase actual, Phase expected, String errorMessage)
            throws InvalidGamePhaseException {
        if (actual != expected) {
            throw new InvalidGamePhaseException(errorMessage);
        }
        return actual;
    }

    public static Phase requirePhaseAtMost(Phase actual, Phase maxPhase)
            throws InvalidGamePhaseException {
        String errorMessage = DEFAULT_PHASE_READY_MESSAGE;
        if (actual.compareTo(maxPhase) > 0) {
            throw new InvalidGamePhaseException(errorMessage);
        }
        return actual;
    }

    public static void requireParticipating(boolean participating)
            throws PlayerNotParticipatingException {
        requireParticipating(participating, DEFAULT_PARTICIPATING_MESSAGE);
    }

    public static void requireParticipating(boolean participating, String errorMessage)
            throws PlayerNotParticipatingException {
        if (!participating) {
            throw new PlayerNotParticipatingException(errorMessage);
        }
    }

    public static void requireNotAlreadyReady(boolean alreadyReady)
            throws AlreadyReadyException {
        requireNotAlreadyReady(alreadyReady, DEFAULT_ALREADY_READY_MESSAGE);
    }

    public static void requireNotAlreadyReady(boolean alreadyReady, String errorMessage)
            throws AlreadyReadyException {
        if (alreadyReady) {
            throw new AlreadyReadyException(errorMessage);
        }
    }

    public static void requireTurnNotTaken(boolean turnTaken)
            throws DuplicateTurnChoiceException {
        requireTurnNotTaken(turnTaken, DEFAULT_TURN_TAKEN_MESSAGE);
    }

    public static void requireTurnNotTaken(boolean turnTaken, String errorMessage)
            throws DuplicateTurnChoiceException {
        if (turnTaken) {
            throw new DuplicateTurnChoiceException(errorMessage);
        }
    }

    public static void requireNotAway(boolean away) throws ConditionValidationException {
        requireNotAway(away, DEFAULT_AWAY_MESSAGE);
    }

    public static void requireNotAway(boolean away, String errorMessage) throws ConditionValidationException {
        if (away) {
            throw new ConditionValidationException(errorMessage);
        }
    }

    public static String requireValidTurnOption(String value)
            throws InvalidTurnOptionException {
        return requireValidTurnOption(value, DEFAULT_TURN_OPTION_MESSAGE);
    }

    public static String requireValidTurnOption(String value, String errorMessage)
            throws InvalidTurnOptionException {
        String normalized = value == null ? "" : value.trim().toLowerCase();

        // example validation for example game
        // requires a number between 1 and 10 (inclusive)
        try {
            int guess = Integer.parseInt(normalized);
            if (guess < 1 || guess > 10) {
                throw new InvalidTurnOptionException("Please enter a number between 1 and 10.");
            }
        } catch (NumberFormatException e) {
            throw new InvalidTurnOptionException("Please enter a valid number.");
        }
        return normalized;
    }
}