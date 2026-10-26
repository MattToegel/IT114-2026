package Project.Common;

/** Seed and dimensions for reconstructing the initial grid. */
public class GridSeedPayload extends Payload {
    private int width;
    private int height;
    private long seed;

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getSeed() { return seed; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
    public void setSeed(long seed) { this.seed = seed; }

    @Override
    public String toString() {
        return super.toString() + " Grid: [" + width + "x" + height + ", seed " + seed + "]";
    }
}
