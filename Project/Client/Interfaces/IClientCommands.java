package Project.Client.Interfaces;

import Project.Exceptions.ValidationException;

public interface IClientCommands {
    boolean connectToServer(String host, int port, String name);

    void disconnectFromServer();

    void sendChatMessage(String text);

    void sendReadySignal() throws ValidationException;

    void sendCardAction(int cardId, int x, int y) throws ValidationException;

    void setDisplayName(String name);
}
