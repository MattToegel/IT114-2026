package Project.Common;

import java.io.Serializable;

/** One typed message shared by the client and server. */
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
