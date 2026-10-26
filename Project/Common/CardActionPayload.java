package Project.Common;

/** Request to play a card from one's private hand at a grid coordinate. */
public class CardActionPayload extends CoordinatePayload {
    private int cardId;

    public int getCardId() { return cardId; }
    public void setCardId(int cardId) { this.cardId = cardId; }

    @Override
    public String toString() {
        return super.toString() + " Card ID: [" + cardId + "]";
    }
}
