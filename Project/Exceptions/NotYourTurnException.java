package Project.Exceptions;

/** Reports an action from someone other than the current player. */
public final class NotYourTurnException extends IT114CustomException {
    /** Create the client-facing explanation for an out-of-turn action. */
    public NotYourTurnException() {
        super("It is not your turn");
    }
}
