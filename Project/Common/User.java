package Project.Common;

import java.util.ArrayList;
import java.util.List;

public class User {
    private long clientId = Constants.DEFAULT_CLIENT_ID;
    private String clientName;
    private boolean ready;
    private boolean turnTaken;
    private int points = 0;
    private final List<Integer> cardIds = new ArrayList<>();

    public User() {
    }

    public User(long clientId, String clientName) {
        this.clientId = clientId;
        this.clientName = clientName;
    }

    /**
     * @return the clientId
     */
    public long getClientId() {
        return clientId;
    }

    /**
     * @return the points
     */
    public int getPoints() {
        return points;
    }

    /**
     * @param points the points to set
     */
    public void setPoints(int points) {
        this.points = points;
    }

    /**
     * @param clientId the clientId to set
     */
    public void setClientId(long clientId) {
        this.clientId = clientId;
    }

    /**
     * @return the username
     */
    public String getClientName() {
        return clientName;
    }

    /**
     * @param username the username to set
     */
    public void setClientName(String username) {
        this.clientName = username;
    }

    public String getDisplayName() {
        return String.format("%s#%s", this.clientName, this.clientId);
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isTurnTaken() {
        return turnTaken;
    }

    public void setTurnTaken(boolean turnTaken) {
        this.turnTaken = turnTaken;
    }

    public List<Integer> getCardIds() {
        return cardIds;
    }

    public boolean hasCardId(int cardId) {
        return cardIds.contains(cardId);
    }

    public void addCardId(int cardId) {
        cardIds.add(cardId);
    }

    public void removeCardId(int cardId) {
        cardIds.remove(Integer.valueOf(cardId));
    }

    public void setCardIds(List<Integer> cardIds) {
        this.cardIds.clear();
        if (cardIds != null) {
            this.cardIds.addAll(cardIds);
        }
    }

    public void clearCardIds() {
        cardIds.clear();
    }

    public void resetGameState() {
        this.ready = false;
        this.turnTaken = false;
        this.points = 0;
        this.cardIds.clear();
    }

    public void reset() {
        this.clientId = Constants.DEFAULT_CLIENT_ID;
        this.clientName = null;
        resetGameState();
    }
}