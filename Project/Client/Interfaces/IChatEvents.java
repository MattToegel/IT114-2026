package Project.Client.Interfaces;

public interface IChatEvents extends IClientEvents {
    void onChatMessageReceived(String message);

    void onSystemMessageReceived(String message);
}