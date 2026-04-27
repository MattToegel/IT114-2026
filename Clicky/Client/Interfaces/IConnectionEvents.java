package Clicky.Client.Interfaces;

import Clicky.Common.User;

public interface IConnectionEvents extends IClientEvents {
    void onConnected(User localUser);

    void onDisconnected();
}
