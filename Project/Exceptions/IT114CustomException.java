package Project.Exceptions;

/** Base class for expected player-action failures shown to the requesting client. */
public abstract class IT114CustomException extends Exception {
    /**
     * Store the explanation that the action handler will send to its requester.
     * @param message the client-facing failure explanation
     */
    protected IT114CustomException(String message) {
        super(message);
    }
}
