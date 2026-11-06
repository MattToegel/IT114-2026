package Project.Exceptions;

/** Reports a guess outside the temporary game range. */
public final class InvalidGuessException extends IT114CustomException {
    /** Create the client-facing explanation for an out-of-range guess. */
    public InvalidGuessException() {
        super("Choose a number from 1 to 5");
    }
}
