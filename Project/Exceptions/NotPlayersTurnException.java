package Project.Exceptions;

public class NotPlayersTurnException extends ValidationException {
    public NotPlayersTurnException(String message) {
        super(message);
    }
}
