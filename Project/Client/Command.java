package Project.Client;

/**
 * Recognized client-side commands.
 */
public enum Command {
    CONNECT("/connect"),
    DISCONNECT("/disconnect"),
    QUIT("/quit"),
    USERS("/users"),
    REVERSE("/reverse"),
    SET_NAME("/name"),
    READY("/ready"),
    TURN("/turn"),// @Deprecated
    VALIDATE_CLIENT("/togglecv"), // toggle client validation (for testing server-side validations)
    GUESS("/guess"), // example game action command
    GRID("/grid"), // print local grid from this client's perspective
    GRID_TEST("/gridtest"), // temporary: send x y value to server grid test handler
    ;

    private final String trigger;

    Command(String trigger) {
        this.trigger = trigger;
    }

    /**
     * Returns the matching Command for the given input text, or null if none
     * matched.
     */
    public static Command fromText(String text) {
        if (text == null)
            return null;
        String lower = text.toLowerCase().trim();
        for (Command c : values()) {
            if (lower.equals(c.trigger) || lower.startsWith(c.trigger + " ")) {
                return c;
            }
        }
        return null;
    }
}
