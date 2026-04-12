package Project.Common;

public class GridCellPayload extends CoordinatePayload {
    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" Value:[%d]", value);
    }
}
