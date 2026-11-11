package Project.Common;

import java.util.List;

/** Public definitions used to display cards by ID and modifier. */
public class CardCatalogPayload extends Payload {
    private final List<Card> cards;

    /** Copy public card definitions into an endpoint message.
     * @param cards public card definitions
     */
    public CardCatalogPayload(List<Card> cards) {
        this.cards = List.copyOf(cards);
    }

    /** Read the public card definitions in this endpoint message.
     * @return the immutable public card definitions
     */
    public List<Card> getCards() { return cards; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with public card definitions
     */
    @Override
    public String toString() {
        return super.toString() + " Catalog: " + cards;
    }
}
