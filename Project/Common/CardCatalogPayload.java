package Project.Common;

import java.util.ArrayList;
import java.util.List;

public class CardCatalogPayload extends Payload {
    private List<Card> cards = new ArrayList<>();

    public List<Card> getCards() {
        return cards;
    }

    public void setCards(List<Card> cards) {
        this.cards = cards == null ? new ArrayList<>() : new ArrayList<>(cards);
    }
}
