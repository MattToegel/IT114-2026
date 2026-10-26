package Project.Common;

/** One grid value, wrapped to the displayed 0-9 range. */
public class Cell {
    private int value;

    public Cell(int value) {
        setValue(value);
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = Math.floorMod(value, 10);
    }

    public void addValue(int amount) {
        setValue(value + amount);
    }
}
