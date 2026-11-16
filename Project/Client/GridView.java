package Project.Client;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.JPanel;

/** Paint the accepted board and report clicks without applying game rules. */
public final class GridView extends JPanel {
    private List<List<Integer>> cells = List.of();
    private final BiConsumer<Integer, Integer> onCellSelected;

    /**
     * Create the board on the EDT; ClientUI supplies its existing playAt method.
     * @param onCellSelected callback receiving a column and row, not a payload
     */
    public GridView(BiConsumer<Integer, Integer> onCellSelected) {
        this.onCellSelected = onCellSelected;
        setPreferredSize(new Dimension(360, 360));
        setBackground(Color.WHITE);
        addMouseListener(new MouseAdapter() {
            /** Forward a permitted cell click to the existing UI action path. */
            @Override
            public void mouseClicked(MouseEvent event) {
                Point cell = cellAt(event.getX(), event.getY());
                if (isEnabled() && cell != null) {
                    GridView.this.onCellSelected.accept(cell.x, cell.y);
                }
            }
        });
    }

    /**
     * Receive copied snapshot values on the EDT, then request a painting pass.
     * @param values the server-confirmed board from ClientSnapshot
     * @param canPlay whether the current snapshot permits a card action
     */
    public void showGrid(List<List<Integer>> values, boolean canPlay) {
        cells = values.stream().map(List::copyOf).toList();
        setEnabled(canPlay);
        repaint();
    }

    /**
     * Map panel pixels to the same whole-pixel cells used by painting.
     * Ignore the unused right/bottom border and a board too small to draw.
     * @param pixelX horizontal mouse position within this panel
     * @param pixelY vertical mouse position within this panel
     * @return the selected column and row, or null outside a drawn cell
     */
    private Point cellAt(int pixelX, int pixelY) {
        if (cells.isEmpty() || cells.get(0).isEmpty()) {
            return null;
        }
        int rows = cells.size();
        int columns = cells.get(0).size();
        int cellWidth = getWidth() / columns;
        int cellHeight = getHeight() / rows;
        if (cellWidth == 0 || cellHeight == 0 || pixelX < 0 || pixelY < 0) {
            return null;
        }
        int x = pixelX / cellWidth;
        int y = pixelY / cellHeight;
        return x < columns && y < rows ? new Point(x, y) : null;
    }

    /**
     * Draw current values only; repainting must never change cells or points.
     * @param g drawing context supplied by Swing
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (cells.isEmpty() || cells.get(0).isEmpty()) {
            g.setColor(Color.DARK_GRAY);
            g.drawString("Ready up to create the board", 12, 24);
            return;
        }
        int cellWidth = getWidth() / cells.get(0).size();
        int cellHeight = getHeight() / cells.size();
        if (cellWidth == 0 || cellHeight == 0) {
            return;
        }
        for (int y = 0; y < cells.size(); y++) {
            for (int x = 0; x < cells.get(y).size(); x++) {
                int value = cells.get(y).get(x);
                int left = x * cellWidth;
                int top = y * cellHeight;
                g.setColor(value % 2 != 0 ? new Color(255, 235, 170) : Color.WHITE);
                g.fillRect(left, top, cellWidth, cellHeight);
                g.setColor(Color.DARK_GRAY);
                g.drawRect(left, top, cellWidth - 1, cellHeight - 1);
                g.drawString(String.valueOf(value), left + 8, top + 20);
            }
        }
    }
}
