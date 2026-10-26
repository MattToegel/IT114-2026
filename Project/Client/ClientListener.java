package Project.Client;

/** One optional observer for the terminal client's synchronized state. */
public interface ClientListener {
    void onState(ClientSnapshot snapshot);
    void onMessage(String message);
}
