package Project.Common;

/** Carries one whole-number action or state value. */
public class IntPayload extends Payload {
    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return super.toString() + " Value: [" + value + "]";
    }
}
