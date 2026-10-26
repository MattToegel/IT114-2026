package Project.Common;

/** Typed location carried by an action or cell update. */
public class CoordinatePayload extends Payload {
    private int x;
    private int y;

    public int getX() { return x; }
    public int getY() { return y; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }

    @Override
    public String toString() {
        return super.toString() + " At: [" + x + ", " + y + "]";
    }
}
