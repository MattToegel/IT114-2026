package Project.Common;

import java.util.ArrayList;
import java.util.List;

public class CardHandPayload extends Payload {
    private List<Integer> cardIds = new ArrayList<>();

    public List<Integer> getCardIds() {
        return cardIds;
    }

    public void setCardIds(List<Integer> cardIds) {
        this.cardIds = cardIds == null ? new ArrayList<>() : new ArrayList<>(cardIds);
    }
}
