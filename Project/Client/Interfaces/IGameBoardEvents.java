package Project.Client.Interfaces;

import java.util.Map;

import Project.Common.Card;
import Project.Common.Grid;
import Project.Common.User;

/**
 * Extends IGameEvents with board-specific callbacks: local grid and hand
 * updates. Consumers that only need phase/turn should implement IGameEvents;
 * consumers that also render the grid and hand implement this interface.
 */
public interface IGameBoardEvents extends IGameFlowEvents {
    void onLocalGridUpdated(Grid localGrid);

    void onLocalHandUpdated(User localPlayer, Map<Integer, Card> cardCatalog);
}
