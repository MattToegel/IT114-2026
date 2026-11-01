package M4.MCCS.Part2;

/**
 * Ordinary identity data for one connected user.
 * ServerThread holds this record; Server uses it for recipients and user lists.
 * Payloads may carry the record between Client and ServerThread.
 */
public class User implements java.io.Serializable {
    private final long clientId;
    private final String clientName;

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

    @Override
    public String toString() {
        return getDisplayName();
    }
}
