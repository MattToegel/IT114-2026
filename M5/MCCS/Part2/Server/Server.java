package M5.MCCS.Part2.Server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import M5.MCCS.Part2.Common.TextFX;
import M5.MCCS.Part2.Common.TextFX.Color;

public enum Server {
    INSTANCE; // Singleton instance

    private int port = 3000;
    private ServerSocket serverSocket = null; // kept as field so shutdown() can close it
    // thread-safe map; multiple ServerThreads may call Server methods concurrently
    private final ConcurrentHashMap<Long, ServerThread> connectedClients = new ConcurrentHashMap<>();
    // ArrayList is sufficient; all access flows through synchronized methods
    private final List<ServerThread> disconnectedBuffer = new ArrayList<>();
    private boolean isRunning = true;

    private long nextClientId = 1; // simple client ID generator

    private void info(String message) {
        System.out.println(TextFX.colorize(String.format("Server: %s", message), Color.YELLOW));
    }

    /**
     * Gracefully disconnect clients
     */
    private void shutdown() {
        try {
            isRunning = false; // stop the accept() loop in start()
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // unblocks the blocking accept() call in start()
            }
            // ConcurrentHashMap.values().forEach() is thread-safe; no
            // ConcurrentModificationException risk.
            // By closing the serverSocket first, no new clients can be added to
            // connectedClients during this iteration.
            connectedClients.values().forEach(serverThread -> serverThread.disconnect());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Server() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            info("JVM is shutting down. Perform cleanup tasks.");
            shutdown();
        }));
    }

    private void start(int port) {
        this.port = port;
        info("Listening on port " + this.port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            this.serverSocket = serverSocket; // store reference so shutdown() can close it
            while (isRunning) {
                info("Waiting for next client");
                Socket incomingClient = serverSocket.accept(); // blocks until a client connects
                info("Client connected");
                // third arg is a callback; ServerThread calls it once streams are open and it
                // is ready
                ServerThread serverThread = new ServerThread(incomingClient, this::onServerThreadInitialized);
                serverThread.start();
                // not added to connectedClients here; that happens inside the callback after
                // setup
            }
        } catch (IOException e) {
            info("Error accepting connection");
            e.printStackTrace();
        } finally {
            info("Server socket closed");
        }
    }

    /**
     * Callback from ServerThread once streams are open and it is ready to
     * send/receive.
     * Registers the client and announces their arrival.
     */
    private synchronized void onServerThreadInitialized(ServerThread serverThread) {
        serverThread.setClientId(nextClientId++);// set then increase client id
        if (nextClientId < 0) { // handle overflow just in case
            nextClientId = 1;
        }
        serverThread.sendClientId(); // send the assigned client ID back to the client
        connectedClients.put(serverThread.getClientId(), serverThread);
        unicastClientStatus(serverThread); // send existing users to new client first
        // Note: broadcastClientStatus includes the new client itself (they receive their own SERVER_JOIN
        // and a SERVER_SYNC about themselves from unicast). This is intentional — filtering it out would
        // add complexity that isn't the focus of this lesson; the client handles it safely via putIfAbsent.
        broadcastClientStatus(serverThread, true, false); // then announce new client to everyone
    }

    /**
     * Internal disconnect: stops the thread, removes it from the map, and
     * broadcasts a notice.
     * Used by handleDisconnect() and can be reused for server-side actions like
     * kicks or timeouts.
     */
    private synchronized void disconnect(ServerThread serverThread) {
        if (!connectedClients.containsKey(serverThread.getClientId())) {
            // already removed (e.g. sendOrDisconnect pruned it during a broadcast)
            // — avoid double-broadcast of the leave status
            return;
        }
        serverThread.sendDisconnectTrigger();
        System.out.println(TextFX.colorize("Client " + serverThread.getDisplayName() + " disconnected.", Color.RED));
        connectedClients.remove(serverThread.getClientId());
        broadcastClientStatus(serverThread, false, false);
    }

    // start region for handle*() methods ===================================
    // handle* methods are the interface ServerThread uses to trigger Server actions

    /**
     * Called when a client requests to disconnect. Delegates to disconnect().
     */
    protected synchronized void handleDisconnect(ServerThread sender) {
        disconnect(sender);
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
    // end region for handle*() methods ===================================

    // start region for data broadcast ==============================

    private void unicastClientStatus(ServerThread incomingServerThread) {
        // send existing clients' info to the newly connected client as a silent sync.

        // Uses a "for each" loop so it can early exit on failure
        // (e.g. if the new client's connection drops during the sync, we don't want to
        // keep trying to send the rest of the clients' info)
        for (ServerThread existingServerThread : connectedClients.values()) {
            boolean success = incomingServerThread.sendClientStatus(
                    existingServerThread.getClientId(),
                    existingServerThread.getClientName(),
                    true,
                    true);
            if (!success) {
                disconnect(incomingServerThread);
                break;
            }
        }
    }

    /**
     * Sends connected clients a status update about a client joining or leaving
     * 
     * @param targetServerThread the client whose status changed
     * @param isJoin             true if joining, false if leaving
     * @param isSync             true if this is a silent background sync, false for
     *                           a real-time join/leave event
     */
    private void broadcastClientStatus(ServerThread targetServerThread, boolean isJoin, boolean isSync) {
        sendOrDisconnect(serverThread -> serverThread.sendClientStatus(
                targetServerThread.getClientId(),
                targetServerThread.getClientName(),
                isJoin,
                isSync));

    }

    /**
     * Sends a message to all connected clients.
     * Any client whose send fails is removed from the map.
     */
    private synchronized void broadcast(ServerThread sender, String message) {
        String senderLabel = sender == null ? "Server" : String.format("User[%s]", sender.getDisplayName());
        final String formatted = String.format("%s: %s", senderLabel, message);
        sendOrDisconnect(serverThread -> serverThread.sendMessage(formatted));
    }
    // end region for data broadcast ==============================

    // utils
    /**
     * Applies sendAction to each connected client, buffering failures, then
     * processes the disconnected buffer. Reduces duplicated removeIf boilerplate.
     */
    private synchronized void sendOrDisconnect(Function<ServerThread, Boolean> sendAction) {
        connectedClients.values().removeIf(serverThread -> {
            boolean success = sendAction.apply(serverThread);
            if (!success) {
                System.out.println(TextFX.colorize("Failed to send message to client " + serverThread.getDisplayName()
                        + ". Removing from connected clients.", Color.RED));
                disconnectedBuffer.add(serverThread);
            }
            return !success;
        });
        processDisconnectedBuffer();
    }

    /**
     * Processes the disconnected buffer by broadcasting disconnects for each
     * buffered client, then clearing the buffer. Used to handle edge cases where a
     * client disconnects in the middle
     */
    private void processDisconnectedBuffer() {
        if (disconnectedBuffer.isEmpty())
            return;
        List<ServerThread> snapshot = new ArrayList<>(disconnectedBuffer);
        disconnectedBuffer.clear();
        snapshot.forEach(st -> broadcastClientStatus(st, false, false));
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
