package Project.Common;

import java.util.List;

/** Private snapshot of one player's card IDs. */
public class CardHandPayload extends Payload {
    private final List<Integer> cardIds;

    /** Copy private card IDs into an owner-only endpoint message.
     * @param cardIds private card IDs
     */
    public CardHandPayload(List<Integer> cardIds) {
        this.cardIds = List.copyOf(cardIds);
    }

    /** Read card IDs sent privately to this owner.
     * @return the immutable private card IDs for this owner
     */
    public List<Integer> getCardIds() { return cardIds; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with this owner's card IDs
     */
    @Override
    public String toString() {
        return super.toString() + " Card IDs: " + cardIds;
    }
}
