package Project.Client.Interfaces;

public interface IClientCommands {
    boolean connectToServer(String host, int port, String name);

    void disconnectFromServer();

    void sendChatMessage(String text);

    void sendReadySignal();

    void sendCardAction(int cardId, int x, int y);

    void setDisplayName(String name);
}
