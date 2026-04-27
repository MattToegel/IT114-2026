package Clicky.Client.Interfaces;

import Clicky.Common.User;

public interface IPlayerStatusEvents extends IClientEvents {
    void onPlayerStatusUpdated(User user);

    void onAllPlayerStatusesReset();
}