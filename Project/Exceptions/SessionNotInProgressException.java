package Project.Exceptions;

/** Reports a game action received outside an active session. */
public final class SessionNotInProgressException extends IT114CustomException {
    /** Create the client-facing explanation for an inactive session. */
    public SessionNotInProgressException() {
        super("The session is not in progress yet");
    }
}
