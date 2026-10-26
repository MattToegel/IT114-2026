package Project.Server;

import Project.Common.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
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

    /** Return the identity held by this connection. */
    public long getClientId() {
        return user.getClientId();
    }

    /** Use the user's display label in connection messages. */
    public String getDisplayName() {
        return user.getDisplayName();
    }

    /** Return this connection's current user snapshot. */
    protected User getUser() {
        return user;
    }

    /** Assign the ID while retaining the accepted name. */
    protected void setClientId(long value) {
        user = new User(value, user.getClientName());
    }

    /** Return the name stored with this connection's identity. */
    protected String getClientName() {
        return user.getClientName();
    }

    /** Report whether the server has assigned a positive client ID. */
    private boolean isRegistered() {
        return getClientId() > 0;
    }

    /** Confirm this connection's assigned identity. */
    protected boolean sendClientId() {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_ID);
        payload.setClientId(getClientId());
        payload.setClientName(getClientName());
        return sendToClient(payload);
    }

    /** Send one presence payload through this connection. */
    protected boolean sendClientStatus(long id, String name, PayloadType type) {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(type);
        payload.setClientId(id);
        payload.setClientName(name);
        return sendToClient(payload);
    }

    /** Write a diagnostic labeled with this client's identity. */
    private void info(String message) {
        LoggerUtil.INSTANCE.info(getDisplayName() + ": " + message);
    }

    /** Notify the server that the streams are ready for communication. */
    private void onInitialized() {
        onInitializationComplete.accept(this);
    }

    /** Notify the server after closing this client connection. */
    private void onDisconnected() {
        server.onServerThreadDisconnected(this);
    }

    /** Route typed client input to its matching process method. */
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
            case READY:
                processReady();
                break;
            case RESET:
                processReset();
                break;
            case GRID_ACTION:
                processGridAction((GridActionPayload) payload);
                break;
            default:
                info("Unsupported client message: " + payload.getPayloadType());
                break;
        }
    }

    /** Read chat data and pass ordinary text to the server handler. */
    private void processMessage(Payload payload) {
        if (isRegistered() && payload.getMessage() != null) {
            server.handleMessage(this, payload.getMessage());
        }
    }

    /** Read the requested text before asking the server to reverse it. */
    private void processReverse(Payload payload) {
        if (!isRegistered()) {
            return;
        }
        server.handleReverseText(this, payload.getMessage() == null ? "" : payload.getMessage());
    }

    /** Ask the server for the connected-user list. */
    private void processGetUserList() {
        if (!isRegistered()) {
            return;
        }
        server.handleGetUserList(this);
    }

    /** Apply this client's request to disconnect. */
    private void processDisconnect() {
        server.handleDisconnect(this);
    }

    /** Pass a registered client's readiness request to the game owner. */
    private void processReady() {
        if (isRegistered()) {
            server.handleReady(this);
        }
    }

    /** Pass a registered client's reset request to the game owner. */
    private void processReset() {
        if (isRegistered()) {
            server.handleReset(this);
        }
    }

    /** Interpret a typed coordinate action for the game owner. */
    private void processGridAction(GridActionPayload payload) {
        if (isRegistered()) {
            server.handleGridAction(this, payload.getX(), payload.getY(), payload.getModifier());
        }
    }

    /** Accept one valid name, then register this initialized connection. */
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

    /** Write this payload unchanged; success means the write completed. */
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
