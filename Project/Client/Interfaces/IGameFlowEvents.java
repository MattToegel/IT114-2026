package Project.Client.Interfaces;

import Project.Common.Phase;

public interface IGameFlowEvents extends IGameEvents {
    void onGamePhaseUpdated(Phase phase);

    void onCurrentTurnUpdated(long currentTurnClientId, String currentTurnDisplayName);

    /**
     * Called when a player transitions from "turn not taken" to "turn taken"
     * during active gameplay.
     */
    default void onPlayerTurnCompleted(long playerId) {
        // intentional no-op; keeps this callback optional for listeners that do not need it
    }

    /**
     * Called when a player's points value changes during active gameplay.
     */
    default void onPlayerPointsChanged(long playerId, int points) {
        // intentional no-op; keeps this callback optional for listeners that do not need it
    }

    /**
     * Called when the server broadcasts a game event message (clientId == GAME_CLIENT_ID).
     */
    default void onGameMessageReceived(String message) {
        // intentional no-op; keeps this callback optional for listeners that do not need it
    }
}
