package Project.Common;

import java.util.List;

import Project.Exceptions.AlreadyReadyException;
import Project.Exceptions.BlankValidationException;
import Project.Exceptions.ConditionValidationException;
import Project.Exceptions.DuplicateTurnChoiceException;
import Project.Exceptions.InvalidGamePhaseException;
import Project.Exceptions.InvalidTurnOptionException;
import Project.Exceptions.NotPlayersTurnException;
import Project.Exceptions.NullValidationException;
import Project.Exceptions.PlayerNotParticipatingException;

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
    private static final String DEFAULT_CURRENT_PLAYER_MESSAGE = "Please wait for your turn.";
    private static final String DEFAULT_TURN_OPTION_MESSAGE = "That turn option is not available.";
    private static final String DEFAULT_OUT_OF_BOUNDS_MESSAGE = "That coordinate is out of bounds.";
    private static final String DEFAULT_INVALID_CELL_VALUE_MESSAGE = "Cell value must be between 0 and 9.";
    private static final String DEFAULT_INVALID_CARD_ID_MESSAGE = "Please choose a valid card id.";
    private static final String DEFAULT_CARD_NOT_IN_HAND_MESSAGE = "That card is not in your hand.";

    private ValidationUtils() {
    }

    public static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean hasValidDimensions(int width, int height) {
        return width > 0 && height > 0;
    }

    public static boolean isInBounds(int x, int y, int width, int height) {
        return hasValidDimensions(width, height)
                && x >= 0
                && y >= 0
                && x < width
                && y < height;
    }

    public static boolean isValidCellValue(int value) {
        return value >= 0 && value <= 9;
    }

    public static boolean isValidCardId(int cardId) {
        return cardId > 0;
    }

    public static void requireInBounds(int x, int y, int width, int height)
            throws ConditionValidationException {
        requireInBounds(x, y, width, height, DEFAULT_OUT_OF_BOUNDS_MESSAGE);
    }

    public static void requireInBounds(int x, int y, int width, int height, String errorMessage)
            throws ConditionValidationException {
        if (!isInBounds(x, y, width, height)) {
            throw new ConditionValidationException(errorMessage);
        }
    }

    public static void requireValidCellValue(int value)
            throws ConditionValidationException {
        requireValidCellValue(value, DEFAULT_INVALID_CELL_VALUE_MESSAGE);
    }

    public static void requireValidCellValue(int value, String errorMessage)
            throws ConditionValidationException {
        if (!isValidCellValue(value)) {
            throw new ConditionValidationException(errorMessage);
        }
    }

    public static void requireValidCardId(int cardId)
            throws ConditionValidationException {
        requireValidCardId(cardId, DEFAULT_INVALID_CARD_ID_MESSAGE);
    }

    public static void requireValidCardId(int cardId, String errorMessage)
            throws ConditionValidationException {
        if (!isValidCardId(cardId)) {
            throw new ConditionValidationException(errorMessage);
        }
    }

    public static void requireCardInHand(List<Integer> cardIds, int cardId)
            throws ConditionValidationException {
        requireCardInHand(cardIds, cardId, DEFAULT_CARD_NOT_IN_HAND_MESSAGE);
    }

    public static void requireCardInHand(List<Integer> cardIds, int cardId, String errorMessage)
            throws ConditionValidationException {
        if (cardIds == null || !cardIds.contains(cardId)) {
            throw new ConditionValidationException(errorMessage);
        }
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

    public static void requireCurrentPlayer(Long currentTurnPlayerId,
            long senderClientId) throws NotPlayersTurnException {
        requireCurrentPlayer(currentTurnPlayerId, senderClientId, DEFAULT_CURRENT_PLAYER_MESSAGE);
    }

    public static void requireCurrentPlayer(Long currentTurnPlayerId,
            long senderClientId,
            String errorMessage) throws NotPlayersTurnException {
        if (currentTurnPlayerId == null || currentTurnPlayerId.longValue() != senderClientId) {
            throw new NotPlayersTurnException(errorMessage);
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