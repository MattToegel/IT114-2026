package Project.Common;

/** Client request to add a modifier around one target cell. */
public class GridActionPayload extends CoordinatePayload {
    private int modifier;

    /** Read the modifier used for one card or grid action.
     * @return the requested addition from 1 through 9
     */
    public int getModifier() { return modifier; }
    /** Store the proposed grid modifier in this endpoint message.
     * @param modifier value supplied by the caller
     */
    public void setModifier(int modifier) { this.modifier = modifier; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with its modifier
     */
    @Override
    public String toString() {
        return super.toString() + " Modifier: [" + modifier + "]";
    }
}
