package Project.Common;

/** Carries an authoritative public true-or-false status. */
public class BoolPayload extends Payload {
    private boolean value;

    /**
     * Read the public status received from the server.
     * @return the boolean value carried by this payload
     */
    public boolean getValue() {
        return value;
    }

    /**
     * Set the status before an endpoint sends this payload.
     * @param value the boolean value to set
     */
    public void setValue(boolean value) {
        this.value = value;
    }

    /** Include the status value when logging this payload. */
    @Override
    public String toString() {
        return super.toString() + String.format(" Value: [%s]", getValue());
    }
}
