package M4.MCCS.Part2;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Objects;


/** Owns one socket, its object streams, read loop, and resource cleanup. */
public class ServerThread extends Thread {
    private final Socket client;
    private volatile long clientId = Constants.DEFAULT_CLIENT_ID;
    private volatile String clientName;
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

    /** Return this handler's stable, process-local thread identifier. */
    public long getClientId() {
        return clientId;
    }

    /** Build the temporary display label used before named clients are introduced. */
    public String getDisplayName() {
        return (clientName == null ? "Client" : clientName) + "#" + clientId;
    }

    /** Assign and confirm this connection's identity before registration. */
    protected void setClientId(long value) {
        clientId = value;
    }
    protected String getClientName() {
        return clientName;
    }
    protected boolean sendClientId() {
        ConnectionPayload reply = new ConnectionPayload();
        reply.setPayloadType(PayloadType.CLIENT_ID);
        reply.setClientId(clientId);
        reply.setClientName(clientName);
        return sendPayload(reply);
    }

    /** Send one presence event through this connection. */
    protected boolean sendClientStatus(long id, String name, PayloadType type) {
        ConnectionPayload event = new ConnectionPayload();
        event.setPayloadType(type);
        event.setClientId(id);
        event.setClientName(name);
        return sendPayload(event);
    }

    /** Write a diagnostic labeled with this client's identity. */
    private void info(String message) {
        System.out.println(getDisplayName() + ": " + message);
    }

    /** Notify the server that the streams are ready for communication. */
    private void onInitialized() {
        onInitializationComplete.accept(this);
    }

    /** Notify the server after closing this client connection. */
    private void onDisconnected() {
        server.handleDisconnect(this);
    }

    /** Interpret typed input and delegate each action to the server. */
    private void processPayload(Payload payload) {
        switch (payload.getPayloadType()) {
            case CLIENT_CONNECT:
                processClientConnect(payload);
                break;
            case MESSAGE:
                if (clientId != Constants.DEFAULT_CLIENT_ID && payload.getMessage() != null) {
                    server.handleMessage(this, payload.getMessage());
                }
                break;
            case REVERSE:
                if (clientId == Constants.DEFAULT_CLIENT_ID) {
                    break;
                }
                server.handleReverseText(this, payload.getMessage() == null ? "" : payload.getMessage());
                break;
            case USERS:
                if (clientId != Constants.DEFAULT_CLIENT_ID) {
                    server.handleGetUserList(this);
                }
                break;
            case DISCONNECT:
                server.handleDisconnect(this);
                break;
            default:
                info("Unsupported client message: " + payload.getPayloadType());
                break;
        }
    }

    /** Accept one valid name, then register this initialized connection. */
    private void processClientConnect(Payload payload) {
        if (clientId != Constants.DEFAULT_CLIENT_ID || !(payload instanceof ConnectionPayload connection)) {
            return;
        }
        String proposed = connection.getClientName();
        if (proposed == null || proposed.isBlank()) {
            disconnect();
            return;
        }
        clientName = proposed.strip();
        nameTimer.cancel();
        onInitialized();
    }

    /** Write a message; success reports a completed write, not a delivery receipt. */
    protected synchronized boolean sendToClient(String payload) {
        Payload outgoing = new Payload();
        outgoing.setPayloadType(PayloadType.MESSAGE);
        outgoing.setMessage(payload);
        return sendPayload(outgoing);
    }

    /** Send a typed reply through the existing guarded output stream. */
    protected synchronized boolean sendPayload(Payload payload) {
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
                            if (clientName == null) {
                                disconnect();
                            }
                        }
                    }, Constants.SETUP_TIMEOUT_MS);
                    while (isRunning) {
                        try {
                            Object received = input.readObject();
                            if (received instanceof Payload payload && payload.getPayloadType() != null) {
                                processPayload(payload);
                            } else {
                                info("Ignoring an invalid payload");
                            }
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
