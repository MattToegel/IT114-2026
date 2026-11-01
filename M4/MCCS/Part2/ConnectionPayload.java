package M4.MCCS.Part2;

/**
 * Carries a client name alongside the ID inherited from Payload.
 * Used for the connection request, identity confirmation, and join/leave events.
 * Client and ServerThread send/process methods bundle or read these fields.
 */
public class ConnectionPayload extends Payload {
    private String clientName;

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String value) {
        clientName = value;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" ClientName: [%s]", getClientName());
    }
}
