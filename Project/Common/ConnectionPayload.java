package Project.Common;

/** A name supplied at connect time or confirmed with an assigned ID. */
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
