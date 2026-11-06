package Project.Exceptions;

/** Reports a game action from a spectator. */
public final class NotParticipantException extends IT114CustomException {
    /** Create the client-facing explanation for a spectator action. */
    public NotParticipantException() {
        super("You are spectating this session");
    }
}
