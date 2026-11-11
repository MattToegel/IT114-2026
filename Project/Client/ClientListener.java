package Project.Client;

/** One optional observer for the terminal client's synchronized state. */
public interface ClientListener {
    /** Receive an immutable game snapshot from Client.
     * @param snapshot current immutable view state
     */
    void onState(ClientSnapshot snapshot);
    /** Receive chat or a game notice from Client.
     * @param message display text
     */
    void onMessage(String message);
}
