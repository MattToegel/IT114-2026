package Project.Common;

/** One server-confirmed cell value at a coordinate. */
public class GridCellPayload extends CoordinatePayload {
    private int value;

    /** Read this authoritative or displayed value.
     * @return the authoritative digit from 0 through 9
     */
    public int getValue() { return value; }
    /** Store a server-confirmed value.
     * @param value server-confirmed cell digit
     */
    public void setValue(int value) { this.value = value; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with the cell value
     */
    @Override
    public String toString() {
        return super.toString() + " Value: [" + value + "]";
    }
}
