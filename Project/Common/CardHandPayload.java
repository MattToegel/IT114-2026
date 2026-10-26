package Project.Common;

import java.util.List;

/** Private snapshot of one player's card IDs. */
public class CardHandPayload extends Payload {
    private final List<Integer> cardIds;

    public CardHandPayload(List<Integer> cardIds) {
        this.cardIds = List.copyOf(cardIds);
    }

    public List<Integer> getCardIds() { return cardIds; }

    @Override
    public String toString() {
        return super.toString() + " Card IDs: " + cardIds;
    }
}
