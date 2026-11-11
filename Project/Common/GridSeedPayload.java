package Project.Common;

/** Seed and dimensions for reconstructing the initial grid. */
public class GridSeedPayload extends Payload {
    private int width;
    private int height;
    private long seed;

    /** Read the board width for a state sync.
     * @return the number of columns, or zero on reset
     */
    public int getWidth() { return width; }
    /** Read the board height for a state sync.
     * @return the number of rows, or zero on reset
     */
    public int getHeight() { return height; }
    /** Read the seed used to rebuild the initial board.
     * @return the seed for reconstructing the starting board
     */
    public long getSeed() { return seed; }
    /** Store the board width in this endpoint message.
     * @param width board width
     */
    public void setWidth(int width) { this.width = width; }
    /** Store the board height in this endpoint message.
     * @param height board height
     */
    public void setHeight(int height) { this.height = height; }
    /** Store the board seed in this endpoint message.
     * @param seed seed for repeatable starting values
     */
    public void setSeed(long seed) { this.seed = seed; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with board dimensions and seed
     */
    @Override
    public String toString() {
        return super.toString() + " Grid: [" + width + "x" + height + ", seed " + seed + "]";
    }
}
