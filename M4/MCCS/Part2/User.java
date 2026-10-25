package M4.MCCS.Part2;

/** One entry in the client's local presence list. */
public class User {
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
        return clientName + "#" + clientId;
    }
}
