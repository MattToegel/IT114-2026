package Project.Common;

/** One server-confirmed cell value at a coordinate. */
public class GridCellPayload extends CoordinatePayload {
    private int value;

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }

    @Override
    public String toString() {
        return super.toString() + " Value: [" + value + "]";
    }
}
