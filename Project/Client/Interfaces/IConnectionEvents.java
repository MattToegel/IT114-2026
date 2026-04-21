package Project.Client.Interfaces;

import Project.Common.User;

public interface IConnectionEvents extends IClientEvents {
    void onConnected(User localUser);

    void onDisconnected();
}
