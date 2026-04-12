package Project.Common;

import java.util.Random;

public class Grid {
    private Cell[][] cells;

    public void setSize(int x, int y) {
        cells = new Cell[x][y];
        for (int i = 0; i < x; i++) {
            for (int j = 0; j < y; j++) {
                cells[i][j] = new Cell();
            }
        }
    }

    public int getWidth() {
        return cells == null ? 0 : cells.length;
    }

    public int getHeight() {
        return cells == null || cells.length == 0 ? 0 : cells[0].length;
    }

    public void clear() {
        if (cells == null) {
            return;
        }
        for (int i = 0; i < cells.length; i++) {
            for (int j = 0; j < cells[i].length; j++) {
                cells[i][j] = null;
            }
        }
        cells = null;
    }

    public void setToZero() {
        if (cells == null) {
            return;
        }
        for (int i = 0; i < cells.length; i++) {
            for (int j = 0; j < cells[i].length; j++) {
                cells[i][j].setValue(0);
            }
        }
    }

    public void setToRandom(long seed) {
        if (cells == null) {
            return;
        }
        Random rand = new Random(seed);
        for (int i = 0; i < cells.length; i++) {
            for (int j = 0; j < cells[i].length; j++) {
                cells[i][j].setValue(rand.nextInt(10));
            }
        }
    }

    public int getValue(int x, int y) {
        if (!isInBounds(x, y)) {
            return 0;
        }
        return cells[x][y].getValue();
    }

    public void setValue(int x, int y, int value) {
        if (!isInBounds(x, y)) {
            return;
        }
        cells[x][y].setValue(value);
    }

    public void applyModifier(int mod, int x, int y) {
        if (!isInBounds(x, y)) {
            return;
        }
        cells[x][y].addValue(mod);

        if (y > 0) {
            cells[x][y - 1].addValue(mod);
        }
        if (y < cells[x].length - 1) {
            cells[x][y + 1].addValue(mod);
        }
        if (x > 0) {
            cells[x - 1][y].addValue(mod);
        }
        if (x < cells.length - 1) {
            cells[x + 1][y].addValue(mod);
        }
    }

    public int countOdd(int x, int y) {
        if (!isInBounds(x, y)) {
            return 0;
        }
        int oddCount = 0;

        if (cells[x][y].getValue() % 2 != 0) {
            oddCount++;
        }
        if (y > 0 && cells[x][y - 1].getValue() % 2 != 0) {
            oddCount++;
        }
        if (y < cells[x].length - 1 && cells[x][y + 1].getValue() % 2 != 0) {
            oddCount++;
        }
        if (x > 0 && cells[x - 1][y].getValue() % 2 != 0) {
            oddCount++;
        }
        if (x < cells.length - 1 && cells[x + 1][y].getValue() % 2 != 0) {
            oddCount++;
        }

        return oddCount;
    }

    public String toGridString() {
        if (cells == null || cells.length == 0 || cells[0].length == 0) {
            return "[grid not initialized]";
        }

        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < cells[0].length; y++) {
            for (int x = 0; x < cells.length; x++) {
                sb.append("[").append(cells[x][y].getValue()).append("]");
            }
            if (y < cells[0].length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private boolean isInBounds(int x, int y) {
        return cells != null
                && x >= 0
                && y >= 0
                && x < cells.length
                && cells.length > 0
                && y < cells[0].length
                && cells[x][y] != null;
    }
}
