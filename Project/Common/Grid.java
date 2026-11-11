package Project.Common;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A seeded rectangular grid shared by server and displaying clients. */
public class Grid {
    private final int width;
    private final int height;
    private final Cell[][] cells;

    /** Create the same seeded rectangular board on server and clients.
     * @param width board width
     * @param height board height
     * @param seed seed for repeatable starting values
     */
    public Grid(int width, int height, long seed) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Grid dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        cells = new Cell[height][width];
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cells[y][x] = new Cell(random.nextInt(10));
            }
        }
    }

    /** Read the board width for a state sync.
     * @return the number of columns
     */
    public int getWidth() {
        return width;
    }

    /** Read the board height for a state sync.
     * @return the number of rows
     */
    public int getHeight() {
        return height;
    }

    /** Check whether a target coordinate is inside the board.
     * @param x target column
     * @param y target row
     * @return true when both coordinates are in bounds
     */
    public boolean contains(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    /** Read this authoritative or displayed value.
     * @param x target column
     * @param y target row
     * @return the digit at the requested coordinate
     */
    public int getValue(int x, int y) {
        return cells[y][x].getValue();
    }

    /** Store a server-confirmed value.
     * @param x target column
     * @param y target row
     * @param value server-confirmed digit to store modulo 10
     */
    public void setValue(int x, int y, int value) {
        cells[y][x].setValue(value);
    }

    /** Apply a modifier to the center and cardinal neighbors in bounds. */
    public List<int[]> applyModifier(int x, int y, int amount) {
        if (!contains(x, y)) {
            throw new IllegalArgumentException("Target is outside the grid");
        }
        int[][] offsets = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        List<int[]> affected = new ArrayList<>();
        for (int[] offset : offsets) {
            int cellX = x + offset[0];
            int cellY = y + offset[1];
            if (contains(cellX, cellY)) {
                cells[cellY][cellX].addValue(amount);
                affected.add(new int[]{cellX, cellY});
            }
        }
        return affected;
    }

    /** Count affected cells whose updated value is odd. */
    public int countOdd(List<int[]> affected) {
        int count = 0;
        for (int[] position : affected) {
            if (getValue(position[0], position[1]) % 2 != 0) {
                count++;
            }
        }
        return count;
    }

    /** Format this value for terminal display or payload logging.
     * @return one row of digits per line
     */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.append(getValue(x, y)).append(' ');
            }
            result.append(System.lineSeparator());
        }
        return result.toString();
    }
}
