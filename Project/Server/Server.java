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

    private volatile boolean isRunning = true;

    private volatile ServerSocket listener;

    private long nextClientId = 1;

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
                new ServerThread(client, this::onServerThreadInitialized).start();
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
        runGameAction(() -> gameServer.syncTo(client.getClientId()));
        if (connectedClients.get(client.getClientId()) != client || !client.isRunning()) {
            return;
        }
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
            runGameAction(() -> gameServer.onClientLeft(client.getUser()));
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
        sendOrDisconnect(recipient -> recipient.sendReverse(sender.getClientId(), message));
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
            LoggerUtil.INSTANCE.info("Removed " + departed.getDisplayName());
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
                LoggerUtil.INSTANCE.info("Port must be between 1 and 65535");
                return;
            }
        } catch (NumberFormatException e) {
            LoggerUtil.INSTANCE.info("Port must be a whole number");
            return;
        }
        Server.INSTANCE.start(port);
    }

    private final List<ServerThread> failedGameSends = new ArrayList<>();

    private int gameDepth;

    private boolean drainingGameFailures;

    // This monitor owns both connection callbacks and game/timer transitions.
    private final GameServer gameServer = new GameServer();

    /** Handle ServerThread's READY request for a registered client under the shared lock; GameServer owns readiness. */
    protected synchronized void handleReady(ServerThread sender) {
        if (connectedClients.get(sender.getClientId()) == sender) {
            runGameAction(() -> gameServer.handleReady(sender.getUser()));
        }
    }

    /** Handle ServerThread's RESET request for a registered client; GameServer decides eligibility. */
    protected synchronized void handleReset(ServerThread sender) {
        if (connectedClients.get(sender.getClientId()) == sender) {
            runGameAction(() -> gameServer.handleReset(sender.getUser()));
        }
    }

    /** Enter a game deadline through the same monitor as client actions; GameServer rejects stale generations. */
    synchronized void onGameTimer(long generation) {
        runGameAction(() -> gameServer.onTimer(generation));
    }

    /**
     * Runs the selected ServerThread send method for each connected client.
     * Server chooses the send action from ordinary game data, never a payload.
     * Failed recipients are removed after the current game transition finishes.
     * @param sendAction the per-connection send, returning local write success
     */
    private void sendGameBroadcast(Function<ServerThread, Boolean> sendAction) {
        for (ServerThread recipient : List.copyOf(connectedClients.values())) {
            sendGameTo(recipient.getClientId(), sendAction);
        }
    }

    /**
     * Runs one ServerThread send and queues a failed recipient for cleanup.
     * @param recipientId the ID of the connection receiving the game update
     * @param sendAction the send method that bundles the ordinary game data
     */
    private void sendGameTo(long recipientId, Function<ServerThread, Boolean> sendAction) {
        ServerThread recipient = connectedClients.get(recipientId);
        if (recipient != null && !sendAction.apply(recipient)) {
            failedGameSends.add(recipient);
        }
    }

    /** Finish the GameServer transition before removing failed recipients, including nested send failures. */
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

    /** Remove failed recipients after game iteration and broadcast departures without recursive cleanup. */
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

    /** Send a phase snapshot to one connected user. */
    void sendGamePhaseTo(long recipientId, Phase phase) {
        sendGameTo(recipientId, client -> client.sendGamePhase(phase));
    }

    /** Announce the phase to all connected users. */
    void sendGamePhaseToAll(Phase phase) {
        sendGameBroadcast(client -> client.sendGamePhase(phase));
    }

    /** Synchronize one participant's readiness to one connected user. */
    void sendReadyStatusTo(long recipientId, long clientId, boolean ready) {
        sendGameTo(recipientId, client -> client.sendReadyStatus(clientId, ready));
    }

    /** Announce a participant's readiness to all connected users. */
    void sendReadyStatusToAll(long clientId, boolean ready) {
        sendGameBroadcast(client -> client.sendReadyStatus(clientId, ready));
    }

    /** Send a server-authored explanation to all connected users. */
    void sendGameMessage(String message) {
        sendGameBroadcast(client -> client.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
    }

    /** Explain a rejected game request only to its sender. */
    void sendGameMessageTo(long recipientId, String message) {
        sendGameTo(recipientId, client -> client.sendMessage(Constants.DEFAULT_CLIENT_ID, message));
    }

    /** Handle ServerThread's typed GUESS request for a registered client; GameServer validates the action. */
    protected synchronized void handleGuess(ServerThread sender, int guess) {
        if (connectedClients.get(sender.getClientId()) == sender) {
            runGameAction(() -> gameServer.handleGuess(sender.getUser(), guess));
        }
    }


    /** Broadcast a participant's public action status without disclosing the private guess. */
    void sendGuessStatusToAll(long clientId, boolean guessed) {
        sendGameBroadcast(client -> client.sendGuessStatus(clientId, guessed));
    }

    /** Sync one observer with a participant's public action status, not its private guess. */
    void sendGuessStatusTo(long recipientId, long clientId, boolean guessed) {
        sendGameTo(recipientId, client -> client.sendGuessStatus(clientId, guessed));
    }

    /** Select only the submitting client for a private guess confirmation; ServerThread packages the value. */
    void sendGuessConfirmationTo(long recipientId, int guess) {
        sendGameTo(recipientId, client -> client.sendGuessConfirmation(guess));
    }

    /** Broadcast GameServer-owned public points after evaluation or reset. */
    void sendPointsToAll(long clientId, int points) {
        sendGameBroadcast(client -> client.sendPoints(clientId, points));
    }

    /** Sync one observer with GameServer-owned public points. */
    void sendPointsTo(long recipientId, long clientId, int points) {
        sendGameTo(recipientId, client -> client.sendPoints(clientId, points));
    }

    /** Announce the next round. */
    void sendRoundToAll(int round) {
        sendGameBroadcast(client -> client.sendRound(round));
    }

    /** Give one observer the current round. */
    void sendRoundTo(long recipientId, int round) {
        sendGameTo(recipientId, client -> client.sendRound(round));
    }
}
