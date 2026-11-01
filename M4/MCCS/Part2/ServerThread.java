package M4.MCCS.Part2;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Objects;


/** Owns one socket, its object streams, read loop, and resource cleanup. */
public class ServerThread extends Thread {
    private final Socket client;
    private volatile User user = new User(Constants.DEFAULT_CLIENT_ID, null);
    private final java.util.Timer nameTimer = new java.util.Timer(true);
    private final Server server;
    private final java.util.function.Consumer<ServerThread> onInitializationComplete;
    private volatile ObjectOutputStream out;
    private volatile boolean isRunning;

    /** Keep the accepted socket and the server callback for this one client. */
    protected ServerThread(Socket client, Server server,
            java.util.function.Consumer<ServerThread> onInitializationComplete) {
        this.client = Objects.requireNonNull(client, "Client socket is required");
        this.server = Objects.requireNonNull(server, "Server is required");
        this.onInitializationComplete = Objects.requireNonNull(onInitializationComplete, "Callback is required");
    }

    /** Report whether this connection has completed setup and remains active. */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Supplies Server with the ID used to identify this connection in its registry.
     *
     * @return the ID stored in this handler's User record
     */
    public long getClientId() {
        return user.getClientId();
    }

    /**
     * Supplies a display label for Server messages and connection diagnostics.
     *
     * @return the display name from this handler's User record
     */
    public String getDisplayName() {
        return user.getDisplayName();
    }

    /**
     * Supplies Server with ordinary user data for lists and connection events.
     * This handler has a User record; Server does not need a payload to inspect it.
     *
     * @return this handler's current User record
     */
    protected User getUser() {
        return user;
    }

    /**
     * Called by Server during registration after it chooses a positive ID.
     * Replaces this handler's User record while keeping the accepted name.
     *
     * @param value the server-assigned positive client ID
     */
    protected void setClientId(long value) {
        user = new User(value, user.getClientName());
    }

    /**
     * Provides this connection's accepted name when confirming its identity.
     *
     * @return the name stored in this handler's User record
     */
    protected String getClientName() {
        return user.getClientName();
    }

    /**
     * Used by process methods to check whether this connection is registered.
     *
     * @return true after Server assigns a positive client ID
     */
    private boolean isRegistered() {
        return getClientId() > 0;
    }

    /**
     * Called by Server during registration to confirm this client's ID and name.
     * Bundles a CLIENT_ID response for this connection only.
     *
     * @return true if sendToClient() completed the local write
     */
    protected boolean sendClientId() {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_ID);
        payload.setClientId(getClientId());
        payload.setClientName(getClientName());
        return sendToClient(payload);
    }

    /**
     * Called by Server.syncExistingUsersTo() on the connection needing a snapshot.
     * Bundles User records as SERVER_SYNC for the client's local user map.
     *
     * @param users the current user records collected by Server
     * @return true if sendToClient() completed the local write
     */
    protected boolean sendServerSync(List<User> users) {
        UserListPayload payload = new UserListPayload(users);
        payload.setPayloadType(PayloadType.SERVER_SYNC);
        return sendToClient(payload);
    }

    /**
     * Called by Server on each recipient's handler when a user joins.
     * Bundles the new user's identity as SERVER_JOIN.
     *
     * @param user the user who joined, not necessarily this recipient
     * @return true if sendToClient() completed the local write
     */
    protected boolean sendServerJoin(User user) {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.SERVER_JOIN);
        payload.setClientId(user.getClientId());
        payload.setClientName(user.getClientName());
        return sendToClient(payload);
    }

    /** Write a diagnostic labeled with this client's identity. */
    private void info(String message) {
        System.out.println(getDisplayName() + ": " + message);
    }

    /** Notify the server that the streams are ready for communication. */
    private void onInitialized() {
        onInitializationComplete.accept(this);
    }

    /**
     * Called when this handler's receive loop ends.
     * Closes its connection and reports the lifecycle event to Server so the
     * registry and other clients can be updated.
     */
    private void onDisconnected() {
        server.onServerThreadDisconnected(this);
    }

    /**
     * Called by this connection's receive loop after reading a Payload.
     * Routes the request to one process method; that method passes ordinary data
     * to Server.handle*() when the client requests an application action.
     *
     * @param payload the request received from this handler's client
     * @throws ClassCastException if a type requires a different payload subclass;
     *         the receive loop catches and reports it
     */
    private void processPayload(Payload payload) {
        if (payload == null || payload.getPayloadType() == null) {
            info("Ignoring an invalid payload");
            return;
        }
        switch (payload.getPayloadType()) {
            case CLIENT_CONNECT:
                processClientConnect((ConnectionPayload) payload);
                break;
            case MESSAGE:
                processMessage(payload);
                break;
            case REVERSE:
                processReverse(payload);
                break;
            case USERS:
                processGetUserList();
                break;
            case DISCONNECT:
                processDisconnect();
                break;
            default:
                info("Unsupported client message: " + payload.getPayloadType());
                break;
        }
    }

    /**
     * Called by processPayload() for a MESSAGE request.
     * Extracts its text and asks Server.handleMessage() to apply the client's intent.
     *
     * @param payload the client's chat request
     */
    private void processMessage(Payload payload) {
        if (isRegistered() && payload.getMessage() != null) {
            server.handleMessage(this, payload.getMessage());
        }
    }

    /**
     * Called by processPayload() for a REVERSE request.
     * Passes text to Server.handleReverseText(), using an empty string when absent.
     *
     * @param payload the client's reversal request
     */
    private void processReverse(Payload payload) {
        if (!isRegistered()) {
            return;
        }
        server.handleReverseText(this, payload.getMessage() == null ? "" : payload.getMessage());
    }

    /**
     * Called by processPayload() for a USERS request.
     * Passes this connection to Server.handleGetUserList() so only its client
     * receives the requested snapshot.
     */
    private void processGetUserList() {
        if (!isRegistered()) {
            return;
        }
        server.handleGetUserList(this);
    }

    /**
     * Called by processPayload() for a DISCONNECT request.
     * Forwards the client's intent to Server.handleDisconnect(); an unexpected
     * connection close uses the onDisconnected() lifecycle callback instead.
     */
    private void processDisconnect() {
        server.handleDisconnect(this);
    }

    /**
     * Called by processPayload() for CLIENT_CONNECT before registration.
     * Accepts one nonblank name, stops the name timeout, and invokes the
     * initialization callback so Server can assign an ID and register this client.
     *
     * @param payload the connection request containing the chosen name
     */
    private void processClientConnect(ConnectionPayload payload) {
        if (isRegistered()) {
            return;
        }
        String proposed = payload.getClientName();
        if (proposed == null || proposed.isBlank()) {
            disconnect();
            return;
        }
        user = new User(getClientId(), proposed.trim());
        nameTimer.cancel();
        onInitialized();
    }

    /**
     * Called by Server to send display text to this handler's client.
     * Builds the MESSAGE payload here, keeping payload construction out of Server.
     *
     * @param clientId the message sender's ID, not the recipient's ID
     * @param message the display text for the recipient
     * @return true if sendToClient() completed the local write
     */
    protected boolean sendMessage(long clientId, String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setClientId(clientId);
        payload.setMessage(message);
        return sendToClient(payload);
    }

    /**
     * Called by Server.handleGetUserList() on the requesting client's handler.
     * Bundles the User records and this recipient's ID for the client's "you" label.
     *
     * @param users the current user records collected by Server
     * @return true if sendToClient() completed the local write
     */
    protected boolean sendUserList(List<User> users) {
        UserListPayload payload = new UserListPayload(users);
        payload.setPayloadType(PayloadType.USERS);
        payload.setClientId(getClientId());
        return sendToClient(payload);
    }

    /**
     * Called by Server on each remaining recipient's handler when a user leaves.
     * Sends the departed ID; clients use their stored User for the leave notice.
     *
     * @param user the user who left
     * @return true if sendToClient() completed the local write
     */
    protected boolean sendLeaveNotice(User user) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.SERVER_LEAVE);
        payload.setClientId(user.getClientId());
        return sendToClient(payload);
    }

    /**
     * Shared transport method used by this handler's action-specific send methods.
     * Writes the payload unchanged and prevents concurrent sends from interleaving.
     * A successful write does not confirm that the client processed the message.
     *
     * @param payload the already bundled outgoing message
     * @return true if the local write completed; false if unavailable or it failed
     */
    protected synchronized boolean sendToClient(Payload payload) {
        if (!isRunning || out == null || client.isClosed()) {
            return false;
        }
        try {
            out.writeObject(payload);
            out.flush();
            out.reset(); // Release previously written objects from the stream's cache.
            return true;
        } catch (IOException e) {
            info("Send failed: " + e.getMessage());
            return false;
        }
    }

    /** Close the classic socket to release blocked platform-thread I/O. */
    protected void disconnect() {
        isRunning = false;
        try {
            client.close();
        } catch (IOException e) {
            info("Could not close the socket: " + e.getMessage());
        }
    }

    /** Initialize both object streams, receive this client's data, and always clean up. */
    @Override
    public void run() {
        // Both endpoints send and flush their stream header before waiting for input.
        try (Socket connection = client) {
            connection.setSoTimeout(Constants.SETUP_TIMEOUT_MS);
            try (ObjectOutputStream output = new ObjectOutputStream(connection.getOutputStream())) {
                out = output;
                output.flush();
                try (ObjectInputStream input = new ObjectInputStream(connection.getInputStream())) {
                    connection.setSoTimeout(0);
                    isRunning = true;
                    nameTimer.schedule(new java.util.TimerTask() {
                        @Override
                        public void run() {
                            if (getClientName() == null) {
                                disconnect();
                            }
                        }
                    }, Constants.SETUP_TIMEOUT_MS);
                    while (isRunning) {
                        try {
                            processPayload((Payload) input.readObject());
                        } catch (ClassCastException e) {
                            info("Ignoring an invalid payload");
                        } catch (ClassNotFoundException e) {
                            info("Unknown message class: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (isRunning) {
                info("Connection ended: " + e.getMessage());
            } else {
                info("Connection closed during setup or cleanup");
            }
        } finally {
            disconnect();
            nameTimer.cancel();
            // Notify the server after this handler closes its connection.
            onDisconnected();
        }
    }
}
