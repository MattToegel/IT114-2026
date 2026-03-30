package M5.MCCS.Part2.Common;

public class User {
    private long clientId = Constants.DEFAULT_CLIENT_ID;
    private String clientName;

    public User() {
    }

    public User(long clientId, String clientName) {
        this.clientId = clientId;
        this.clientName = clientName;
    }

    /**
     * @return the clientId
     */
    public long getClientId() {
        return clientId;
    }

    /**
     * @param clientId the clientId to set
     */
    public void setClientId(long clientId) {
        this.clientId = clientId;
    }

    /**
     * @return the username
     */
    public String getClientName() {
        return clientName;
    }

    /**
     * @param username the username to set
     */
    public void setClientName(String username) {
        this.clientName = username;
    }

    public String getDisplayName() {
        return String.format("%s#%s", this.clientName, this.clientId);
    }

    public void reset() {
        this.clientId = Constants.DEFAULT_CLIENT_ID;
        this.clientName = null;
    }
}