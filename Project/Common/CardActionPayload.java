package Project.Common;

/** Request to play a card from one's private hand at a grid coordinate. */
public class CardActionPayload extends CoordinatePayload {
    private int cardId;

    /** Read the proposed card ID from this endpoint message.
     * @return the proposed card ID
     */
    public int getCardId() { return cardId; }
    /** Store the proposed card ID in this endpoint message.
     * @param cardId proposed card ID
     */
    public void setCardId(int cardId) { this.cardId = cardId; }

    /** Format this value for terminal display or payload logging.
     * @return the payload text with its proposed card ID
     */
    @Override
    public String toString() {
        return super.toString() + " Card ID: [" + cardId + "]";
    }
}
