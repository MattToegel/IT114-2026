package M4.MCCS.Part2;

import java.io.Serializable;

/**
 * Serializable base for messages exchanged by Client and ServerThread.
 * Send methods fill its fields; processPayload() routes it by PayloadType.
 * Application logic in Server receives ordinary values instead of this object.
 */
public class Payload implements Serializable {
    private PayloadType payloadType;
    private long clientId;
    private String message;

    public PayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(PayloadType value) {
        payloadType = value;
    }

    public long getClientId() {
        return clientId;
    }

    public void setClientId(long value) {
        clientId = value;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String value) {
        message = value;
    }

    @Override
    public String toString() {
        return String.format("Payload[%s] Client Id [%s] Message: [%s]",
                getPayloadType(), getClientId(), getMessage());
    }
}
