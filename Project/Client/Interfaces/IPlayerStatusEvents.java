package Project.Client.Interfaces;

import Project.Common.User;

public interface IPlayerStatusEvents extends IClientEvents {
    void onPlayerStatusUpdated(User user);

    void onAllPlayerStatusesReset();
}