package M4.MCCS.Part2;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Accepts connections and selects the recipients of each message. */
public class Server {

    private final ConcurrentHashMap<Long, ServerThread> connectedClients = new ConcurrentHashMap<>();
    private final List<ServerThread> disconnectedBuffer = new ArrayList<>();
    private volatile boolean isRunning = true;
    private volatile ServerSocket listener;
    private long nextClientId = 1;

    /** Register resource cleanup for JVM shutdown. */
    public Server() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    /** Accept connections and give each socket its own handler thread. */
    private void start(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            listener = serverSocket;
            System.out.println("Server listening on " + port);
            while (isRunning) {
                Socket client = serverSocket.accept();
                new ServerThread(client, this, this::onServerThreadInitialized).start();
            }
        } catch (IOException e) {
            if (isRunning) {
                System.out.println("Server error: " + e.getMessage());
            }
        } finally {
            shutdown();
        }
    }

    /** Stop acceptance and close current connections without holding the registry lock. */
    private void shutdown() {
        isRunning = false;
        try {
            if (listener != null) {
                listener.close();
            }
        } catch (IOException e) {
            System.out.println("Could not close listener: " + e.getMessage());
        }
        // Socket close does not wait for a handler's callback or hold the registry lock.
        for (ServerThread client : connectedClients.values()) {
            client.disconnect();
        }
    }

    /**
     * Receives ServerThread's initialization callback after its streams open and
     * processClientConnect() accepts a name. Assigns and confirms a positive ID
     * before adding this connection to the active clients.
     *
     * @param client the initialized connection joining the server
     */
    private synchronized void onServerThreadInitialized(ServerThread client) {
        if (!client.isRunning()) {
            return;
        }
        client.setClientId(nextAvailableClientId());
        if (!client.sendClientId()) {
            client.disconnect();
            return;
        }
        if (!syncExistingUsersTo(client) || !client.isRunning()) {
            return;
        }
        connectedClients.put(client.getClientId(), client);
        sendServerJoin(client);
    }

    /**
     * Collects the current User records for initialization or a /users request.
     * The recipient's ServerThread bundles the snapshot; a failed send closes it.
     *
     * @param recipient the connection that needs the snapshot
     * @return true if the local write completed, not a delivery acknowledgment
     */
    private boolean syncExistingUsersTo(ServerThread recipient) {
        List<User> users = new ArrayList<>();
        for (ServerThread existing : connectedClients.values()) {
            users.add(existing.getUser());
        }
        if (!recipient.sendServerSync(users)) {
            recipient.disconnect();
            return false;
        }
        return true;
    }


    /**
     * Called by onServerThreadInitialized() after the newcomer is registered.
     * Asks each recipient's ServerThread to bundle the new User as a join event.
     *
     * @param client the newly registered connection
     */
    private void sendServerJoin(ServerThread client) {
        sendOrDisconnect(recipient -> recipient.sendServerJoin(client.getUser()));
    }

    /**
     * Called while registering a connection to choose an unused positive ID.
     * Restarts at 1 after overflow so reserved nonpositive IDs are never assigned.
     *
     * @return a positive ID not currently used by a connected client
     */
    private long nextAvailableClientId() {
        while (true) {
            if (nextClientId <= 0) {
                nextClientId = 1;
            }
            long candidate = nextClientId++;
            if (!connectedClients.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    /** Close this connection and queue one leave event on its first removal. */
    private void removeClient(ServerThread client) {
        client.disconnect();
        if (connectedClients.remove(client.getClientId(), client)) {
            disconnectedBuffer.add(client);
        }
    }

    /**
     * Handles an explicit client disconnect request from
     * {@code ServerThread.processDisconnect()}. Uses the same cleanup as a lost connection.
     *
     * @param client the connection that requested disconnection
     */
    protected synchronized void handleDisconnect(ServerThread client) {
        onServerThreadDisconnected(client);
    }

    /**
     * Cleans up after ServerThread reports a closed connection or handleDisconnect()
     * handles a client request. Repeated calls do not produce duplicate leave notices.
     *
     * @param client the connection to remove
     */
    protected synchronized void onServerThreadDisconnected(ServerThread client) {
        removeClient(client);
        processDisconnectedBuffer();
    }

    /**
     * Handles a /users request from ServerThread.processGetUserList().
     * Reuses the initial-sync path to send a current snapshot only to the requester.
     *
     * @param requester the connection asking for the current user list
     */
    protected synchronized void handleGetUserList(ServerThread requester) {
        if (!syncExistingUsersTo(requester)) {
            removeClient(requester);
            processDisconnectedBuffer();
        }
    }

    /**
     * Handles a client's intent to send chat, passed here by
     * {@code ServerThread.processMessage()} after it reads the payload.
     * Formats ordinary text and asks each recipient's ServerThread to send it.
     *
     * @param sender the connection that requested the action
     * @param text the chat text supplied by that client
     */
    protected synchronized void handleMessage(ServerThread sender, String text) {
        String message = sender.getDisplayName() + ": " + text;
        sendBroadcast(sender.getClientId(), message);
    }

    /**
     * Handles a client's reverse request, passed here by
     * {@code ServerThread.processReverse()} as ordinary text.
     * Broadcasts the reversed result using the requesting client's identity.
     *
     * @param sender the connection that requested the reversal
     * @param text the text to reverse
     */
    protected synchronized void handleReverseText(ServerThread sender, String text) {
        String message = sender.getDisplayName() + ": " + new StringBuilder(text).reverse();
        sendBroadcast(sender.getClientId(), message);
    }

    /**
     * Routes chat or a server notice to all connected clients.
     * ServerThread.sendMessage() creates the payload for each recipient.
     *
     * @param clientId the sender's ID, or DEFAULT_CLIENT_ID for a server notice
     * @param message the display text to send
     */
    private void sendBroadcast(long clientId, String message) {
        sendOrDisconnect(recipient -> recipient.sendMessage(clientId, message));
    }

    /** Try each current recipient and queue failed connections for removal. */
    private void sendPass(Function<ServerThread, Boolean> sendAction) {
        for (ServerThread client : List.copyOf(connectedClients.values())) {
            if (!sendAction.apply(client)) {
                removeClient(client);
            }
        }
    }

    /** Complete a send pass before processing its disconnect notices. */
    private void sendOrDisconnect(Function<ServerThread, Boolean> sendAction) {
        sendPass(sendAction);
        processDisconnectedBuffer();
    }

    /**
     * Finishes cleanup after a send pass or a connection-close callback.
     * Drains queued departures, including any further failures found while sending
     * leave notices, instead of starting a recursive cleanup call.
     */
    private void processDisconnectedBuffer() {
        while (!disconnectedBuffer.isEmpty()) {
            ServerThread departed = disconnectedBuffer.remove(0);
            System.out.println("Removed " + departed.getDisplayName());
            sendLeaveNotice(departed);
        }
    }

    /**
     * Called by processDisconnectedBuffer() for each removed connection.
     * Asks the remaining ServerThreads to send the departure; sendPass() queues
     * any further failures for the existing cleanup loop.
     *
     * @param departed the removed connection whose User identifies the departure
     */
    private void sendLeaveNotice(ServerThread departed) {
        sendPass(recipient -> recipient.sendLeaveNotice(departed.getUser()));
    }

    /** Validate the optional port argument and start the server. */
    public static void main(String[] args) {
        int port = 3000;
        try {
            if (args.length > 0) {
                port = Integer.parseInt(args[0]);
            }
            if (port < 1 || port > 65535) {
                System.out.println("Port must be between 1 and 65535");
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("Port must be a whole number");
            return;
        }
        new Server().start(port);
    }
}
