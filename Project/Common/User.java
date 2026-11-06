package Project.Common;

/**
 * Public identity and session progress for one connected user.
 * ServerThread holds this record; Server uses it for recipients and user lists.
 * Payloads may carry the record between Client and ServerThread.
 */
public class User implements java.io.Serializable {
    private final long clientId;
    private final String clientName;
    private volatile boolean ready;
    private volatile boolean submitted;
    private volatile int points;
    // TODO: Remove the temporary guess and its accessors when implementing your project game.
    // transient omits this field when User is written to an ObjectOutputStream.
    // Received copies start at 0; GUESS_CONFIRM sends the owner's value privately.
    private transient int guess;

    public User(long clientId, String clientName) {
        this.clientId = clientId;
        this.clientName = clientName;
    }

    public long getClientId() {
        return clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public String getDisplayName() {
        return (clientName == null ? "Client" : clientName) + "#" + clientId;
    }

    /** The server sets this state; clients retain the received copy for display. */
    public boolean isReady() {
        return ready;
    }

    /**
     * Set authoritative readiness on the server or apply its update on a client.
     * @param ready whether this user joined the ready window
     */
    public void setReady(boolean ready) {
        this.ready = ready;
    }

        /**
     * Report public action completion without exposing a private guess.
     * @return true after a valid submission or individual-turn timeout
     */
    public boolean isSubmitted() {
        return submitted;
    }

    /**
     * Record public action completion on the server or apply its update on a client.
     * @param submitted whether this user has acted this round
     */
    public void setSubmitted(boolean submitted) {
        this.submitted = submitted;
    }

        /**
     * Report the public total calculated by GameServer.
     * @return this participant's current session points
     */
    public int getPoints() {
        return points;
    }

    /**
     * Set GameServer's total on the server or apply a public score update on a client.
     * @param points this participant's current session total
     */
    public void setPoints(int points) {
        this.points = points;
    }

    /**
     * Read GameServer's temporary guess for round evaluation.
     * @return the server-side guess, or zero when none is stored
     */
    public int getGuess() {
        return guess;
    }

    /**
     * Called by GameServer to record or clear this round's temporary guess.
     * @param guess a number from 1 through 5, or zero to clear it
     */
    public void setGuess(int guess) {
        this.guess = guess;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
