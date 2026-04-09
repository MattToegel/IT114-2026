package Project.Server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import Project.Common.LoggerUtil;

public enum Server {
    INSTANCE; // Singleton instance

    // Static initializer block to configure server-side logging
    static {
        LoggerUtil.LoggerConfig config = new LoggerUtil.LoggerConfig();
        config.setFileSizeLimit(2048 * 1024); // 2MB
        config.setFileCount(1);
        config.setLogLocation("server.log");
        LoggerUtil.INSTANCE.setConfig(config);
    }

    private int port = 3000;
    private ServerSocket serverSocket = null; // kept as field so shutdown() can close it
    private GameServer gameServer;
    private boolean isRunning = true;
    boolean isolateNonParticipantMessages = true; // can be toggled based on what's logical for the project
    private final BaseServer baseServer = new BaseServer();

    /**
     * Gracefully disconnect clients
     */
    private void shutdown() {
        try {
            isRunning = false; // stop the accept() loop in start()
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // unblocks the blocking accept() call in start()
            }
            baseServer.connectedClients.values().forEach(serverThread -> serverThread.disconnect());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Server() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            baseServer.info("JVM is shutting down. Perform cleanup tasks.");
            shutdown();
        }));
    }

    private void start(int port) {
        this.port = port;
        baseServer.info("Listening on port " + this.port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            this.serverSocket = serverSocket; // store reference so shutdown() can close it
            while (isRunning) {
                baseServer.info("Waiting for next client");
                Socket incomingClient = serverSocket.accept(); // blocks until a client connects
                baseServer.info("Client connected");
                ServerThread serverThread = new ServerThread(incomingClient, this::onServerThreadInitialized);
                serverThread.start();
            }
        } catch (IOException e) {
            baseServer.info("Error accepting connection");
            e.printStackTrace();
        } finally {
            baseServer.info("Server socket closed");
        }
    }

    /**
     * Callback from ServerThread once streams are open and it is ready to
     * send/receive.
     */
    private synchronized void onServerThreadInitialized(ServerThread serverThread) {
        baseServer.registerClient(serverThread);
        notifyGameServerClientJoined(serverThread); // trigger game server join hook
    }

    // start region for handle*() methods ===================================

    protected synchronized void handleGuess(ServerThread sender, String guess) {
        if (!isGameServerActive()) {
            return;
        }
        try {
            gameServer.handleGuess(sender, guess);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Game server handleGuess failed", e);
        }
    }

    /**
     * Passes user's turn action to the game session
     */
    @Deprecated
    protected synchronized void handleTurn(ServerThread sender, String action) {
        if (!isGameServerActive()) {
            return;
        }
        try {
            gameServer.handleTurn(sender, action);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Game server handleTurn failed", e);
        }
    }

    /**
     * Passes user's intent to join the game session to the game session
     */
    protected synchronized void handleReady(ServerThread sender) {
        if (!isGameServerActive()) {
            return;
        }
        try {
            gameServer.handleReady(sender);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Game server handleReady failed", e);
        }
    }

    /**
     * Called when a client requests to disconnect.
     */
    protected synchronized void handleDisconnect(ServerThread sender) {
        baseServer.disconnect(sender);
        notifyGameServerClientRemoved(sender);
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

    // Utils

    /**
     * Sets the active game server implementation.
     */
    public synchronized void setGameServer(GameServer gameServer) {
        this.gameServer = gameServer;
        if (this.gameServer == null) {
            baseServer.info("GameServer disabled");
        } else {
            baseServer.info("GameServer set to " + this.gameServer.getClass().getSimpleName());
        }
    }

    /**
     * Returns the current connected client count.
     */
    protected synchronized int getConnectedClientCount() {
        return baseServer.getConnectedClientCount();
    }

    /**
     * Returns a snapshot of currently connected clients.
     */
    protected synchronized Collection<ServerThread> getConnectedClientsSnapshot() {
        return baseServer.getConnectedClientsSnapshot();
    }

    /**
     * Checks whether a client is currently connected.
     */
    protected synchronized boolean hasClient(ServerThread client) {
        return baseServer.hasClient(client);
    }

    /**
     * Sends a message to all connected clients.
     */
    protected synchronized void broadcast(ServerThread sender, String message) {

        if (isolateNonParticipantMessages
                && sender != null
                && isGameServerActive()
                && !gameServer.isActivePlayer(sender)) {
            // Non-participant chat is multicast to other non-participants only.
            // Used to prevent non-participants from spoiling a game
            List<ServerThread> nonParticipants = getConnectedClientsSnapshot().stream()
                    .filter(client -> client.getClientId() != sender.getClientId())
                    .filter(client -> !gameServer.isActivePlayer(client))
                    .collect(Collectors.toList());

            baseServer.multicastMessage(sender, message, nonParticipants);
            return;
        }

        baseServer.broadcast(sender, message);
    }

    /**
     * Applies sendAction to each connected client, buffering failures, then
     * processes the disconnected buffer.
     */
    protected synchronized void sendOrDisconnect(Function<ServerThread, Boolean> sendAction) {
        baseServer.sendOrDisconnect(sendAction);
    }

    /**
     * Sends data to a single client. If send fails, client is removed.
     */
    protected synchronized void unicast(ServerThread target, Function<ServerThread, Boolean> sendAction) {
        baseServer.unicast(target, sendAction);
    }

    /**
     * Sends data to a subset of clients. Any failed sends are disconnected.
     */
    protected synchronized void multicast(Collection<ServerThread> targets,
            Function<ServerThread, Boolean> sendAction) {
        baseServer.multicast(targets, sendAction);
    }

    private boolean isGameServerActive() {
        return gameServer != null;
    }

    private void notifyGameServerClientJoined(ServerThread serverThread) {
        if (!isGameServerActive()) {
            return;
        }
        // onClientRemoved can be called during initialization to trigger
        // onPlayerJoined/onPlayerLeft hooks
        try {
            gameServer.onSpectatorJoined(serverThread);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Game server onSpectatorJoined failed", e);
        }
    }

    private void notifyGameServerClientRemoved(ServerThread serverThread) {
        if (!isGameServerActive()) {
            return;
        }
        try {
            gameServer.onClientRemoved(serverThread);
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Game server onClientRemoved failed", e);
        }
    }

    public static void main(String[] args) {
        LoggerUtil.INSTANCE.info("Server Starting");
        Server server = Server.INSTANCE;
        server.setGameServer(new GameServer());
        int port = 3000;
        try {
            port = Integer.parseInt(args[0]);
        } catch (Exception e) {
            // use default port
        }
        server.start(port);
        LoggerUtil.INSTANCE.info("Server Stopped");
    }
}
