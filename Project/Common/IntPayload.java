package Project.Common;

/** Carries one whole-number action or state value. */
public class IntPayload extends Payload {
    private int value;

    /**
     * Read the typed whole-number value used by a guess or public update.
     * @return the payload's numeric value
     */
    public int getValue() {
        return value;
    }

    /**
     * Set a typed whole-number value before endpoint transport.
     * @param value the guess, score, or round number to carry
     */
    public void setValue(int value) {
        this.value = value;
    }

    /** Include the numeric value when logging this payload. */
    @Override
    public String toString() {
        return super.toString() + " Value: [" + value + "]";
    }
}
