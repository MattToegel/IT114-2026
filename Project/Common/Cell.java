package Project.Common;

public class Cell {
    private int value;

    private int wrap(int v) {
        return Math.floorMod(v, 10);
    }

    public int getValue() {
        return value;
    }

    public void addValue(int v) {
        value = wrap(value + v);
    }

    public void setValue(int v) {
        value = wrap(v);
    }
}
