package Project.Common;

/** Typed location carried by an action or cell update. */
public class CoordinatePayload extends Payload {
    private int x;
    private int y;

    /** Read the target column from this endpoint message.
     * @return the target column
     */
    public int getX() { return x; }
    /** Read the target row from this endpoint message.
     * @return the target row
     */
    public int getY() { return y; }
    /** Store the target column in this endpoint message.
     * @param x target column
     */
    public void setX(int x) { this.x = x; }
    /** Store the target row in this endpoint message.
     * @param y target row
     */
    public void setY(int y) { this.y = y; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with its coordinate
     */
    @Override
    public String toString() {
        return super.toString() + " At: [" + x + ", " + y + "]";
    }
}
