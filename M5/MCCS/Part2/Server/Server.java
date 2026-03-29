package M5.MCCS.Part2.Server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public enum Server {
    INSTANCE; // Singleton instance

    private int port = 3000;
    // thread-safe map; multiple ServerThreads may call Server methods concurrently
    private final ConcurrentHashMap<Long, ServerThread> connectedClients = new ConcurrentHashMap<>();
    private boolean isRunning = true;

    private void start(int port) {
        this.port = port;
        System.out.println("Listening on port " + this.port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (isRunning) {
                System.out.println("Waiting for next client");
                Socket incomingClient = serverSocket.accept(); // blocks until a client connects
                System.out.println("Client connected");
                // third arg is a callback; ServerThread calls it once streams are open and it
                // is ready
                ServerThread serverThread = new ServerThread(incomingClient, this, this::onServerThreadInitialized);
                serverThread.start();
                // not added to connectedClients here; that happens inside the callback after
                // setup
            }
        } catch (IOException e) {
            System.err.println("Error accepting connection");
            e.printStackTrace();
        } finally {
            System.out.println("Server socket closed");
        }
    }

    /**
     * Callback from ServerThread once streams are open and it is ready to
     * send/receive.
     * Registers the client and announces their arrival.
     */
    private synchronized void onServerThreadInitialized(ServerThread serverThread) {
        connectedClients.put(serverThread.getClientId(), serverThread);
        broadcast(null, String.format("*User[%s] connected*", serverThread.getClientId()));
    }

    /**
     * Internal disconnect: stops the thread, removes it from the map, and
     * broadcasts a notice.
     * Used by handleDisconnect() and can be reused for server-side actions like
     * kicks or timeouts.
     */
    private synchronized void disconnect(ServerThread serverThread) {
        serverThread.disconnect();
        connectedClients.remove(serverThread.getClientId());
        broadcast(null, String.format("User[%s] disconnected", serverThread.getClientId()));
    }

    /**
     * Sends a message to all connected clients.
     * Any client whose send fails is removed from the map.
     */
    private synchronized void broadcast(ServerThread sender, String message) {
        String senderLabel = sender == null ? "Server" : String.format("User[%s]", sender.getClientId());
        final String formatted = String.format("%s: %s", senderLabel, message);
        connectedClients.values().removeIf(serverThread -> !serverThread.sendToClient(formatted));
    }

    // handle* methods are the interface ServerThread uses to trigger Server actions

    /**
     * Called when a client requests to disconnect. Delegates to disconnect().
     */
    protected synchronized void handleDisconnect(ServerThread sender) {
        disconnect(sender);
    }

    /**
     * Sends the connected user list only to the requesting client.
     * The requester's entry is tagged with "(you)".
     */
    protected synchronized void handleGetUserList(ServerThread serverThread) {
        StringBuilder sb = new StringBuilder("Connected users:\n");
        connectedClients.forEach((id, st) -> {
            if (id == serverThread.getClientId()) {
                sb.append(String.format("  User[%s] (you)\n", id));
            } else {
                sb.append(String.format("  User[%s]\n", id));
            }
        });
        serverThread.sendToClient(sb.toString().trim());
    }

    /** Reverses the text and broadcasts the result. */
    protected synchronized void handleReverseText(ServerThread sender, String text) {
        StringBuilder sb = new StringBuilder(text);
        sb.reverse();
        broadcast(sender, sb.toString());
    }

    /** Broadcasts a chat message from the sender to all clients. */
    protected synchronized void handleMessage(ServerThread sender, String text) {
        broadcast(sender, text);
    }

    public static void main(String[] args) {
        System.out.println("Server Starting");
        Server server = Server.INSTANCE;
        int port = 3000;
        try {
            port = Integer.parseInt(args[0]);
        } catch (Exception e) {
            // use default port
        }
        server.start(port);
        System.out.println("Server Stopped");
    }
}
