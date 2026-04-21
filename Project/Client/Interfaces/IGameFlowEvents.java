package Project.Client.Interfaces;

import Project.Common.Phase;

public interface IGameFlowEvents extends IGameEvents {
    void onGamePhaseUpdated(Phase phase);

    void onCurrentTurnUpdated(long currentTurnClientId, String currentTurnDisplayName);

    /**
     * Called when the server broadcasts a game event message (clientId == GAME_CLIENT_ID).
     * Default no-op so only views that care (e.g. GameEventsView) need to override.
     */
    default void onGameMessageReceived(String message) {
        // intentional no-op; override to display game event messages
    }
}
