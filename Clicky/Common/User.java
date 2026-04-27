package Clicky.Common;

public class User {
    private long clientId = Constants.DEFAULT_CLIENT_ID;
    private String clientName;
    private boolean ready;
    private boolean turnTaken;
    private int guess = 0;// example user data
    private int points = 0;
    private int clicks = 0;
    private int totalClicks = 0;
    private boolean away = false;

    /**
     * @return the away
     */
    public boolean isAway() {
        return away;
    }

    /**
     * @param away the away to set
     */
    public void setAway(boolean away) {
        this.away = away;
    }

    public User() {
    }

    public User(long clientId, String clientName) {
        this.clientId = clientId;
        this.clientName = clientName;
    }

    /**
     * @return the clicks
     */
    public int getClicks() {
        return clicks;
    }

    /**
     * @param clicks the clicks to set
     */
    public void setClicks(int clicks) {
        this.clicks = clicks;

    }

    // TODO May need to add total clicks and sync it
    /**
     * Increments the click count for this user by 1.
     * Also increments totalClicks which tracks the total clicks across rounds for
     * this user.
     */
    public void incrementClicks() {
        this.clicks++;
        this.totalClicks++;
    }

    /**
     * @return the clientId
     */
    public long getClientId() {
        return clientId;
    }

    /**
     * @return the totalClicks
     */
    public int getTotalClicks() {
        return totalClicks;
    }

    /**
     * @param totalClicks the totalClicks to set
     */
    public void setTotalClicks(int totalClicks) {
        this.totalClicks = totalClicks;
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
     * @return the guess
     */
    public int getGuess() {
        return guess;
    }

    /**
     * @param guess the guess to set
     */
    public void setGuess(int guess) {
        this.guess = guess;
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

    public void resetGameState() {
        this.ready = false;
        this.turnTaken = false;
        this.guess = 0; // example user data
        this.points = 0;
        this.clicks = 0;
        this.totalClicks = 0;

    }

    public void reset() {
        this.clientId = Constants.DEFAULT_CLIENT_ID;
        this.clientName = null;
        resetGameState();
    }

    public static User copyOf(User source) {
        if (source == null) {
            return null;
        }
        User copy = new User(source.getClientId(), source.getClientName());
        copy.setReady(source.isReady());
        copy.setTurnTaken(source.isTurnTaken());
        copy.setAway(source.isAway());
        copy.setPoints(source.getPoints());
        copy.setClicks(source.getClicks());
        return copy;
    }
}