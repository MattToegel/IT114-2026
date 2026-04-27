package Clicky.Common;

public class BoolPayload extends Payload {
    private boolean value;

    /**
     * @return the boolean value carried by this payload
     */
    public boolean getValue() {
        return value;
    }

    /**
     * @param value the boolean value to set
     */
    public void setValue(boolean value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" Value: [%s]", getValue());
    }
}
