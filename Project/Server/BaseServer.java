package Project.Server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import Project.Common.LoggerUtil;
import Project.Common.TextFX;
import Project.Common.TextFX.Color;

/**
 * Base server plumbing: connection management, broadcast/unicast helpers,
 * and disconnect handling.
 */
public class BaseServer {

    // thread-safe map; multiple ServerThreads may call methods concurrently
    protected final ConcurrentHashMap<Long, ServerThread> connectedClients = new ConcurrentHashMap<>();
    // ArrayList is sufficient; all access flows through synchronized methods
    protected final List<ServerThread> disconnectedBuffer = new ArrayList<>();

    protected long nextClientId = 1; // simple client ID generator

    protected void info(String message) {
        LoggerUtil.INSTANCE.info(TextFX.colorize("Server: " + message, Color.YELLOW));
    }

    /**
     * Registers a newly initialized client.
     */
    protected synchronized void registerClient(ServerThread serverThread) {
        serverThread.setClientId(nextClientId++); // set then increase client id
        if (nextClientId < 0) { // handle overflow just in case
            nextClientId = 1;
        }
        serverThread.sendClientId(); // send the assigned client ID back to the client
        connectedClients.put(serverThread.getClientId(), serverThread);
        unicastClientStatus(serverThread); // send existing users to new client first
        // Note: broadcastClientStatus includes the new client itself (they receive
        // their own SERVER_JOIN and a SERVER_SYNC about themselves from unicast).
        // This is intentional — filtering it out would add complexity;
        // the client handles it safely via putIfAbsent.
        broadcastClientStatus(serverThread, true, false); // then announce new client to everyone
    }

    /**
     * Internal disconnect: stops the thread, removes it from the map, and
     * broadcasts a notice.
     */
    protected synchronized void disconnect(ServerThread serverThread) {
        if (!connectedClients.containsKey(serverThread.getClientId())) {
            // already removed (e.g. sendOrDisconnect pruned it during a broadcast)
            // — avoid double-broadcast of the leave status
            return;
        }
        serverThread.sendDisconnectTrigger();
        LoggerUtil.INSTANCE
                .info(TextFX.colorize("Client " + serverThread.getDisplayName() + " disconnected.", Color.RED));
        connectedClients.remove(serverThread.getClientId());
    }

    /**
     * Sends existing clients' info to a newly connected client as a silent sync.
     */
    private void unicastClientStatus(ServerThread incomingServerThread) {
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
    protected void broadcastClientStatus(ServerThread targetServerThread, boolean isJoin, boolean isSync) {
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
    protected synchronized void broadcast(ServerThread sender, String message) {
        String senderLabel = sender == null ? "Server" : String.format("%s", sender.getDisplayName());
        final String formatted = String.format("%s: %s", senderLabel, message);
        sendOrDisconnect(serverThread -> serverThread.sendMessage(formatted));
    }

    /**
     * Sends a message to a subset of clients using the same message format as
     * broadcast(). Any target whose send fails is disconnected.
     */
    protected synchronized void multicastMessage(ServerThread sender, String message,
            Collection<ServerThread> targets) {
        String senderLabel = sender == null ? "Server" : String.format("%s", sender.getDisplayName());
        final String formatted = String.format("%s: %s", senderLabel, message);
        multicast(targets, serverThread -> serverThread.sendMessage(formatted));
    }

    /**
     * Returns the current connected client count.
     */
    protected synchronized int getConnectedClientCount() {
        return connectedClients.size();
    }

    /**
     * Returns a snapshot of currently connected clients.
     */
    protected synchronized Collection<ServerThread> getConnectedClientsSnapshot() {
        return List.copyOf(connectedClients.values());
    }

    /**
     * Checks whether a client is currently connected.
     */
    protected synchronized boolean hasClient(ServerThread client) {
        return client != null && connectedClients.containsKey(client.getClientId());
    }

    /**
     * Applies sendAction to each connected client, buffering failures, then
     * processes the disconnected buffer. Reduces duplicated removeIf boilerplate.
     */
    protected synchronized void sendOrDisconnect(Function<ServerThread, Boolean> sendAction) {
        connectedClients.values().removeIf(serverThread -> {
            boolean success = sendAction.apply(serverThread);
            if (!success) {
                LoggerUtil.INSTANCE
                        .info(TextFX.colorize("Failed to send message to client " + serverThread.getDisplayName()
                                + ". Removing from connected clients.", Color.RED));
                disconnectedBuffer.add(serverThread);
            }
            return !success;
        });
        processDisconnectedBuffer();
    }

    /**
     * Sends data to a single client. If send fails, client is removed.
     */
    protected synchronized void unicast(ServerThread target, Function<ServerThread, Boolean> sendAction) {
        if (target == null) {
            return;
        }
        boolean success = sendAction.apply(target);
        if (!success) {
            LoggerUtil.INSTANCE.info(TextFX.colorize(
                    "Failed to send message to client " + target.getDisplayName()
                            + ". Removing from connected clients.",
                    Color.RED));
            connectedClients.remove(target.getClientId());
            disconnectedBuffer.add(target);
            processDisconnectedBuffer();
        }
    }

    /**
     * Sends data to a subset of clients. Any failed sends are disconnected.
     */
    protected synchronized void multicast(Collection<ServerThread> targets,
            Function<ServerThread, Boolean> sendAction) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (ServerThread target : targets) {
            boolean success = sendAction.apply(target);
            if (!success) {
                LoggerUtil.INSTANCE.info(TextFX.colorize(
                        "Failed to send message to client " + target.getDisplayName()
                                + ". Removing from connected clients.",
                        Color.RED));
                connectedClients.remove(target.getClientId());
                disconnectedBuffer.add(target);
            }
        }
        processDisconnectedBuffer();
    }

    /**
     * Processes the disconnected buffer by broadcasting disconnects for each
     * buffered client, then clearing the buffer.
     */
    protected void processDisconnectedBuffer() {
        if (disconnectedBuffer.isEmpty())
            return;
        List<ServerThread> snapshot = new ArrayList<>(disconnectedBuffer);
        disconnectedBuffer.clear();
        snapshot.forEach(st -> {
            broadcastClientStatus(st, false, false);
        });
    }
}
