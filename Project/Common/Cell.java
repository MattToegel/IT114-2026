package Project.Common;

/** One grid value, wrapped to the displayed 0-9 range. */
public class Cell {
    private int value;

    /** Create one grid digit for server rules or client display.
     * @param value any integer; stored modulo 10
     */
    public Cell(int value) {
        setValue(value);
    }

    /** Read this authoritative or displayed value.
     * @return the wrapped digit from 0 through 9
     */
    public int getValue() {
        return value;
    }

    /** Store a server-confirmed value.
     * @param value any integer; stored modulo 10
     */
    public void setValue(int value) {
        this.value = Math.floorMod(value, 10);
    }

    /** Add a modifier and wrap this digit from zero through nine.
     * @param amount addition to apply
     */
    public void addValue(int amount) {
        setValue(value + amount);
    }
}
