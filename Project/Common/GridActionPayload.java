package Project.Common;

/** Client request to add a modifier around one target cell. */
public class GridActionPayload extends CoordinatePayload {
    private int modifier;

    public int getModifier() { return modifier; }
    public void setModifier(int modifier) { this.modifier = modifier; }

    @Override
    public String toString() {
        return super.toString() + " Modifier: [" + modifier + "]";
    }
}
