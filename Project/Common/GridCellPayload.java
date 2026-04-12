package Project.Common;

public class GridCellPayload extends Payload {
    private int x;
    private int y;
    private int value;

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" X:[%d] Y:[%d] Value:[%d]", x, y, value);
    }
}
