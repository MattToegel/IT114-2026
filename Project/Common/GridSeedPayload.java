package Project.Common;

public class GridSeedPayload extends Payload {
    private long seed;
    private int width;
    private int height;

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" Seed:[%d] Width:[%d] Height:[%d]", seed, width, height);
    }
}
