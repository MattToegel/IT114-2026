package Project.Client.Interfaces;

import Project.Common.User;

public interface IPlayerStatusEvents extends IClientEvents {
    void onLocalPlayerStatusUpdated(User localPlayer);

    void onPlayerStatusUpdated(long playerId, boolean ready, boolean turnTaken, int points);

    void onAllPlayerStatusesReset();
}