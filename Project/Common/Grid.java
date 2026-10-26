package Project.Common;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A seeded rectangular grid shared by server and displaying clients. */
public class Grid {
    private final int width;
    private final int height;
    private final Cell[][] cells;

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

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean contains(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    public int getValue(int x, int y) {
        return cells[y][x].getValue();
    }

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
