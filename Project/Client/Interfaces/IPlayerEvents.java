package Project.Client.Interfaces;

import java.util.Map;

import Project.Common.User;

public interface IPlayerEvents extends IClientEvents {
    void onPlayersUpdated(Map<Long, User> players);
}