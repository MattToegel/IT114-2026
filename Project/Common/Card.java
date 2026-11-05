package Project.Common;

import java.io.Serializable;

/** Immutable card definition; ID identifies a modifier, not a hand owner. */
public class Card implements Serializable {
    private final int id;
    private final int modifier;

    /** Create an immutable public card definition.
     * @param id card ID
     * @param modifier value supplied by the caller
     */
    public Card(int id, int modifier) {
        this.id = id;
        this.modifier = modifier;
    }

    /** Read the public ID that identifies this card.
     * @return the public card ID
     */
    public int getId() { return id; }
    /** Read the modifier used for one card or grid action.
     * @return the server-defined addition from 1 through 9
     */
    public int getModifier() { return modifier; }

    /** Format this value for terminal display or payload logging.
     * @return the card ID and modifier for display
     */
    @Override
    public String toString() {
        return "Card " + id + " (+" + modifier + ")";
    }
}
