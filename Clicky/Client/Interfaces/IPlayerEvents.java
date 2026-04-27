package Clicky.Client.Interfaces;

import java.util.Map;

import Clicky.Common.User;

public interface IPlayerEvents extends IClientEvents {
    void onPlayersUpdated(Map<Long, User> players);
}