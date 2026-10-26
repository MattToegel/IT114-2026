package M4.MCCS.Part2;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Objects;

import M4.MCCS.Part2.Constants;

/** Owns one socket, its object streams, read loop, and resource cleanup. */
public class ServerThread extends Thread {
    private final Socket client;
    private final long clientId = threadId();
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
        return "User[" + clientId + "]";
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

    /** Interpret wire commands or delegate ordinary text to the server. */
    private void processMessage(String message) {
        if (message.startsWith(Constants.COMMAND_TRIGGER + ",")) {
            String[] parts = message.split(",", 3);
            switch (parts[1]) {
                case "disconnect":
                    server.handleDisconnect(this);
                    return;
                case "users":
                    server.handleGetUserList(this);
                    return;
                case "reverse":
                    server.handleReverseText(this, parts.length > 2 ? parts[2] : "");
                    return;
                default:
                    break;
            }
        }
        server.handleMessage(this, message);
    }

    /** Write a message; success reports a completed write, not a delivery receipt. */
    protected synchronized boolean sendToClient(String payload) {
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
                    onInitialized();
                    while (isRunning) {
                        try {
                            Object received = input.readObject();
                            if (received instanceof String message) {
                                processMessage(message);
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
            // Notify the server after this handler closes its connection.
            onDisconnected();
        }
    }
}
