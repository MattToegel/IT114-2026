package Project.Client;

import Project.Client.ClientSnapshot.Player;
import Project.Common.Card;
import java.util.List;

/** View callbacks for accepted client updates. Override the events your view needs. */
public interface ClientListener {
    /** Report whether the client has an open connection.
     * @param connected true when the connection is open
     */
    default void onConnectionChanged(boolean connected) {}

    /** Deliver chat or a notice.
     * @param message display text
     * @param chat true for player chat, false for notices and replies
     */
    default void onMessage(String message, boolean chat) {}

    /** Deliver updated public player rows.
     * @param clientId the local player ID
     * @param players immutable public player rows
     */
    default void onPlayersChanged(long clientId, List<Player> players) {}

    /** Report the accepted game status.
     * @param phase current phase
     * @param round current round
     * @param currentTurnId owner of the current turn, or the default ID
     */
    default void onGameChanged(String phase, int round, long currentTurnId) {}

    /** Deliver an updated board.
     * @param cells immutable rows of values
     */
    default void onBoardChanged(List<List<Integer>> cells) {}

    /** Deliver the local player’s private hand.
     * @param cards immutable list of held card definitions
     */
    default void onHandChanged(List<Card> cards) {}

    /** Report a countdown change.
     * @param remainingMillis estimated time in milliseconds, or zero
     */
    default void onTimerChanged(int remainingMillis) {}
}
