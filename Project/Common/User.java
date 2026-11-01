package Project.Common;

/**
 * Ordinary identity data for one connected user.
 * ServerThread holds this record; Server uses it for recipients and user lists.
 * Payloads may carry the record between Client and ServerThread.
 */
public class User implements java.io.Serializable {
    private final long clientId;
    private final String clientName;
    private volatile boolean ready;

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

    @Override
    public String toString() {
        return getDisplayName();
    }
}
