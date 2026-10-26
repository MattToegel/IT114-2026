package Project.Server;

import Project.Common.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Accepts connections and selects the recipients of each message. */
public enum Server {
    INSTANCE;

    static {
        LoggerUtil.LoggerConfig config = new LoggerUtil.LoggerConfig();
        config.setFileSizeLimit(2048 * 1024);
        config.setFileCount(1);
        config.setLogLocation("server.log");
        LoggerUtil.INSTANCE.setConfig(config);
    }

    private final ConcurrentHashMap<Long, ServerThread> connectedClients = new ConcurrentHashMap<>();
    private final List<ServerThread> disconnectedBuffer = new ArrayList<>();
    private final List<ServerThread> failedGameSends = new ArrayList<>();
    private int gameDepth;
    private boolean drainingGameFailures;
    private volatile boolean isRunning = true;
    private volatile ServerSocket listener;
    private long nextClientId = 1;
    // This monitor owns both connection callbacks and game/timer transitions.
    private final GameServer gameServer = new GameServer(this);

    /** Register resource cleanup for JVM shutdown. */
    private Server() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    /** Accept connections and give each socket its own handler thread. */
    private void start(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            listener = serverSocket;
            LoggerUtil.INSTANCE.info("Server listening on " + port);
            while (isRunning) {
                Socket client = serverSocket.accept();
                new ServerThread(client, this, this::onServerThreadInitialized).start();
            }
        } catch (IOException e) {
            if (isRunning) {
                LoggerUtil.INSTANCE.info("Server error: " + e.getMessage());
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
            LoggerUtil.INSTANCE.info("Could not close listener: " + e.getMessage());
        }
        // Socket close does not wait for a handler's callback or hold the registry lock.
        for (ServerThread client : connectedClients.values()) {
            client.disconnect();
        }
    }

    /** Register a handler only after its object streams are ready. */
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
        runGameAction(() -> gameServer.syncTo(client));
        if (connectedClients.get(client.getClientId()) != client || !client.isRunning()) {
            return;
        }
        sendServerJoin(client);
    }

    /** Send the current user-list snapshot to one connection. */
    private boolean syncExistingUsersTo(ServerThread recipient) {
        List<User> users = new ArrayList<>();
        for (ServerThread existing : connectedClients.values()) {
            users.add(existing.getUser());
        }
        if (!sendServerSync(recipient, users)) {
            recipient.disconnect();
            return false;
        }
        return true;
    }

    /** Bundle the existing-user snapshot for one new or requesting client. */
    private boolean sendServerSync(ServerThread recipient, List<User> users) {
        UserListPayload payload = new UserListPayload(users);
        payload.setPayloadType(PayloadType.SERVER_SYNC);
        return recipient.sendToClient(payload);
    }

    /** Bundle a join event and announce it to the connected clients. */
    private void sendServerJoin(ServerThread client) {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.SERVER_JOIN);
        payload.setClientId(client.getClientId());
        payload.setClientName(client.getClientName());
        sendBroadcast(payload);
    }

    /** Choose a positive ID that is not assigned to a connected client. */
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
            runGameAction(() -> gameServer.onClientLeft(client));
        }
    }

    /** Apply a disconnect requested by this client. */
    protected synchronized void handleDisconnect(ServerThread client) {
        onServerThreadDisconnected(client);
    }

    /** Remove a closed connection and send queued leave notices. */
    protected synchronized void onServerThreadDisconnected(ServerThread client) {
        removeClient(client);
        processDisconnectedBuffer();
    }

    /** Send a requested user list through the same structured snapshot path. */
    protected synchronized void handleGetUserList(ServerThread requester) {
        if (!syncExistingUsersTo(requester)) {
            removeClient(requester);
            processDisconnectedBuffer();
        }
    }

    /** Format client chat before sending it to the connected clients. */
    protected synchronized void handleMessage(ServerThread sender, String text) {
        sendMessage(sender.getDisplayName() + ": " + text);
    }

    /** Reverse the requested text before sending its reply. */
    protected synchronized void handleReverseText(ServerThread sender, String text) {
        sendMessage(sender.getDisplayName() + ": " + new StringBuilder(text).reverse());
    }

    /** Apply a registered client's readiness request under the shared server lock. */
    protected synchronized void handleReady(ServerThread sender) {
        if (connectedClients.get(sender.getClientId()) == sender) {
            runGameAction(() -> gameServer.handleReady(sender));
        }
    }

    /** Let an active participant clear the example session and begin again. */
    protected synchronized void handleReset(ServerThread sender) {
        if (connectedClients.get(sender.getClientId()) == sender) {
            runGameAction(() -> gameServer.handleReset(sender));
        }
    }

    /** Pass one coordinate and modifier to the current game session. */
    protected synchronized void handleGridAction(ServerThread sender, int x, int y, int modifier) {
        if (connectedClients.get(sender.getClientId()) == sender) {
            runGameAction(() -> gameServer.handleGridAction(sender, x, y, modifier));
        }
    }

    /** Timer callbacks enter through the same monitor as client requests. */
    synchronized void onGameTimer(long generation) {
        runGameAction(() -> gameServer.onTimer(generation));
    }

    /** Send one game payload to every connected client. */
    void sendGameBroadcast(Payload payload) {
        for (ServerThread recipient : List.copyOf(connectedClients.values())) {
            if (!recipient.sendToClient(payload)) {
                failedGameSends.add(recipient);
            }
        }
    }

    /** Send one game payload privately; defer failed-recipient cleanup. */
    void sendGameTo(ServerThread recipient, Payload payload) {
        if (!recipient.sendToClient(payload)) {
            failedGameSends.add(recipient);
        }
    }

    /** Finish a game transition before removing failed send recipients. */
    private void runGameAction(Runnable action) {
        gameDepth++;
        try {
            action.run();
        } finally {
            gameDepth--;
            if (gameDepth == 0) {
                drainFailedGameSends();
            }
        }
    }

    /** Remove failed recipients once no game-state loop is in progress. */
    private void drainFailedGameSends() {
        if (drainingGameFailures) {
            return;
        }
        drainingGameFailures = true;
        try {
            do {
                while (!failedGameSends.isEmpty()) {
                    ServerThread failed = failedGameSends.remove(0);
                    removeClient(failed);
                }
                processDisconnectedBuffer();
            } while (!failedGameSends.isEmpty() || !disconnectedBuffer.isEmpty());
        } finally {
            drainingGameFailures = false;
        }
    }

    /** Bundle a MESSAGE payload and broadcast it. */
    private void sendMessage(String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        sendBroadcast(payload);
    }

    /** Send the supplied payload to every ready handler without changing its data. */
    private void sendBroadcast(Payload payload) {
        sendOrDisconnect(recipient -> recipient.sendToClient(payload));
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

    /** Drain leave notices without recursively broadcasting further failures. */
    private void processDisconnectedBuffer() {
        while (!disconnectedBuffer.isEmpty()) {
            ServerThread departed = disconnectedBuffer.remove(0);
            LoggerUtil.INSTANCE.info("Removed " + departed.getDisplayName());
            sendLeaveNotice(departed);
        }
    }

    /** Send the typed leave event without starting another cleanup pass. */
    private void sendLeaveNotice(ServerThread departed) {
        sendPass(recipient -> recipient.sendClientStatus(
                departed.getClientId(), departed.getClientName(), PayloadType.SERVER_LEAVE));
    }

    /** Validate the optional port argument and start the server. */
    public static void main(String[] args) {
        int port = 3000;
        try {
            if (args.length > 0) {
                port = Integer.parseInt(args[0]);
            }
            if (port < 1 || port > 65535) {
                LoggerUtil.INSTANCE.info("Port must be between 1 and 65535");
                return;
            }
        } catch (NumberFormatException e) {
            LoggerUtil.INSTANCE.info("Port must be a whole number");
            return;
        }
        Server.INSTANCE.start(port);
    }
}
