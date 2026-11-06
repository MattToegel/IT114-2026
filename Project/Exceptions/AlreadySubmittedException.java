package Project.Exceptions;

/** Reports a second action in the same round. */
public final class AlreadySubmittedException extends IT114CustomException {
    /** Create the client-facing explanation for a repeated action. */
    public AlreadySubmittedException() {
        super("You already acted this round");
    }
}
