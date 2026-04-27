package Clicky.Client.Interfaces;

import Clicky.Exceptions.ValidationException;

public interface IClientCommands {
    boolean connectToServer(String host, int port, String name);

    void disconnectFromServer();

    void sendChatMessage(String text);

    void sendReadySignal() throws ValidationException;

    void sendClickSignal() throws ValidationException;

    void setDisplayName(String name);

    void sendAwayToggle() throws ValidationException;
}
