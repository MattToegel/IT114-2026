package Project.Common;

import java.util.List;

/** Public definitions used to display cards by ID and modifier. */
public class CardCatalogPayload extends Payload {
    private final List<Card> cards;

    public CardCatalogPayload(List<Card> cards) {
        this.cards = List.copyOf(cards);
    }

    public List<Card> getCards() { return cards; }

    @Override
    public String toString() {
        return super.toString() + " Catalog: " + cards;
    }
}
