package Clicky.Client.Interfaces;

import Clicky.Common.Phase;

public interface IGameFlowEvents extends IGameEvents {
    void onGamePhaseUpdated(Phase phase);

    /**
     * Called when a player transitions from "turn not taken" to "turn taken"
     * during active gameplay.
     */
    default void onPlayerTurnCompleted(long playerId) {
        // intentional no-op; keeps this callback optional for listeners that do not
        // need it
    }

    /**
     * Called when a player's points value changes during active gameplay.
     */
    default void onPlayerPointsChanged(long playerId, int points) {
        // intentional no-op; keeps this callback optional for listeners that do not
        // need it
    }

    /**
     * Called when the server broadcasts a game event message (clientId ==
     * GAME_CLIENT_ID).
     */
    default void onGameMessageReceived(String message) {
        // intentional no-op; keeps this callback optional for listeners that do not
        // need it
    }

    default void onPlayerClicksChanged(long playerId, int clicks) {
        // intentional no-op; keeps this callback optional for listeners that do not
        // need it
    }
}
