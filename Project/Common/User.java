package Project.Common;

/** One connected user's ID and display name. */
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
