package Project.Client;

import Project.Common.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Scanner;



/** Terminal input stays on the main thread; one background thread receives messages. */
public enum Client {

    INSTANCE;

    static {
        LoggerUtil.LoggerConfig config = new LoggerUtil.LoggerConfig();
        config.setFileSizeLimit(2048 * 1024);
        config.setFileCount(1);
        config.setLogLocation("client.log");
        LoggerUtil.INSTANCE.setConfig(config);
    }

    private volatile Socket server;

    private ObjectOutputStream out;

    private boolean isRunning = true;

    private String clientName;

    private long clientId = Constants.DEFAULT_CLIENT_ID;

    private Grid grid;

    private java.util.Map<Integer, Card> cardCatalog = new java.util.HashMap<>();

    private java.util.List<Integer> hand = new java.util.ArrayList<>();

    private java.util.Map<Long, Integer> publicHandCounts = new java.util.HashMap<>();

    private final java.util.concurrent.ConcurrentHashMap<Long, User> knownUsers = new java.util.concurrent.ConcurrentHashMap<>();

    private String phase = "INACTIVE";

    private int round;

    private long currentTurnId;

    private long timerDeadlineNanos;

    private volatile ClientListener listener;

    private enum Command {
        CONNECT("/connect"), DISCONNECT("/disconnect"), QUIT("/quit"),
        USERS("/users"), REVERSE("/reverse"), SET_NAME("/name"),
        READY("/ready"), RESET("/reset"), CARD("/card"), HAND("/hand"), GRID("/grid");

        private final String trigger;

        /** Store the slash command spelling without changing argument text. */
        Command(String trigger) {
            this.trigger = trigger;
        }

        /** Match a command token without requiring a particular letter case. */
        static Command fromText(String token) {
            for (Command command : values()) {
                if (command.trigger.equalsIgnoreCase(token)) {
                    return command;
                }
            }
            return null;
        }
    }

    /** Check whether the current socket and output stream are available. */
    public synchronized boolean isConnected() {
        return server != null && !server.isClosed() && out != null;
    }

    /**
     * Use the terminal socket path for a Swing connection after validating its endpoint.
     * @param name chosen player name
     * @param host server hostname
     * @param port server port
     * @return true when the local connection request was sent
     * @throws IOException if the name request cannot be written
     */
    public synchronized boolean connectToServer(String name, String host, int port) throws IOException {
        if (isConnected() || name == null || name.isBlank()
                || host == null || host.isBlank() || port < 1 || port > 65535) {
            return false;
        }
        clientName = name.trim();
        if (!connect(host.trim(), port)) {
            return false;
        }
        try {
            sendConnectionData();
            publishState();
            return true;
        } catch (IOException e) {
            closeServerConnection(server);
            throw e;
        }
    }

    /** Process a Swing chat entry through the same parser as terminal input.
     * @param text chat or slash command
     * @throws IOException when a local write fails
     */
    public void sendChat(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!processClientCommand(text)) {
            sendMessage(text);
        }
    }

    /** Send the Swing view's readiness intent.
     * @throws IOException when the local write fails
     */
    public void requestReady() throws IOException {
        sendReady();
    }

    /** Send the Swing view's reset intent.
     * @throws IOException when the local write fails
     */
    public void requestReset() throws IOException {
        sendReset();
    }

    /** Send a card play through the same endpoint as terminal input.
     * @param cardId private hand ID
     * @param x target column
     * @param y target row
     * @throws IOException when the local write fails
     */
    public void playCard(int cardId, int x, int y) throws IOException {
        sendCardAction(cardId, x, y);
    }

    /** Close only this client's current socket; Server observes its departure. */
    public synchronized void disconnectClient() {
        closeServerConnection(server);
    }

    /** Register one view observer and send it the current snapshot.
     * @param observer the active view
     */
    public synchronized void attachListener(ClientListener observer) {
        listener = java.util.Objects.requireNonNull(observer);
        observer.onState(snapshot());
    }

    /** Let only the current observer detach itself.
     * @param observer the view requesting detachment
     * @return true when this view was still active
     */
    public synchronized boolean detachListener(ClientListener observer) {
        if (listener != observer) {
            return false;
        }
        listener = null;
        return true;
    }

    /** Capture the current socket so delayed view cleanup cannot close a later connection.
     * @param observer the view being closed
     * @return a socket-specific close action, or a no-op for an old view
     */
    public synchronized Runnable detachListenerForClose(ClientListener observer) {
        if (listener != observer) {
            return () -> {};
        }
        listener = null;
        Socket connection = server;
        return () -> closeServerConnection(connection);
    }

    /** Copy synchronized ordinary values for Swing without exposing network payloads.
     * @return an immutable current view snapshot
     */
    public synchronized ClientSnapshot snapshot() {
        java.util.List<ClientSnapshot.Player> players = new java.util.ArrayList<>();
        knownUsers.values().stream()
                .sorted(java.util.Comparator.comparingLong(User::getClientId))
                .forEach(user -> players.add(new ClientSnapshot.Player(
                        user.getClientId(), user.getDisplayName(),
                        user.isReady(), user.isSubmitted(), user.isMyTurn(), user.getPoints(),
                        publicHandCounts.getOrDefault(user.getClientId(), 0))));
        java.util.List<java.util.List<Integer>> cells = new java.util.ArrayList<>();
        if (grid != null) {
            for (int y = 0; y < grid.getHeight(); y++) {
                java.util.List<Integer> row = new java.util.ArrayList<>();
                for (int x = 0; x < grid.getWidth(); x++) {
                    row.add(grid.getValue(x, y));
                }
                cells.add(row);
            }
        }
        long remaining = timerDeadlineNanos == 0 ? 0
                : Math.max(0, (timerDeadlineNanos - System.nanoTime()) / 1_000_000);
        return new ClientSnapshot(isConnected(), clientId, phase, round, currentTurnId,
                (int) Math.min(Integer.MAX_VALUE, remaining), players, cells,
                new java.util.ArrayList<>(cardCatalog.values()), hand);
    }

    /** Notify the active view after a state-changing endpoint message. */
    private void publishState() {
        ClientListener observer = listener;
        if (observer != null) {
            observer.onState(snapshot());
        }
    }

    /** Show ordinary chat and game notices in the active view.
     * @param message display text
     */
    private void publishMessage(String message) {
        ClientListener observer = listener;
        if (observer != null && message != null) {
            observer.onMessage(message);
        }
    }

    /** Open streams in header-safe order and start a background receiver. */
    private synchronized boolean connect(String host, int port) {
        if (isConnected()) {
            LoggerUtil.INSTANCE.info("Already connected; use /disconnect first");
            return false;
        }
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host, port), Constants.SETUP_TIMEOUT_MS);
            candidate.setSoTimeout(Constants.SETUP_TIMEOUT_MS);
            ObjectOutputStream output = new ObjectOutputStream(candidate.getOutputStream());
            output.flush();
            ObjectInputStream input = new ObjectInputStream(candidate.getInputStream());
            candidate.setSoTimeout(0);
            server = candidate;
            out = output;
            new Thread(() -> listenToServer(candidate, input)).start();
            LoggerUtil.INSTANCE.info("Connection opened");
            return true;
        } catch (IOException e) {
            LoggerUtil.INSTANCE.info("Connection failed: " + e.getMessage());
            try {
                candidate.close();
            } catch (IOException closeError) {
                LoggerUtil.INSTANCE.info("Could not close failed connection: " + closeError.getMessage());
            }
            return false;
        }
    }

    /** Handle a recognized slash command and leave ordinary chat text to the caller. */
    private boolean processClientCommand(String text) throws IOException {
        String[] parts = text.strip().split("\\s+", 2);
        Command command = Command.fromText(parts[0]);
        if (command == null) {
            return false;
        }
        String arguments = parts.length == 2 ? parts[1] : "";
        switch (command) {
            case CONNECT:
                if (clientName == null || clientName.isBlank()) {
                    LoggerUtil.INSTANCE.info("Set a name with /name <name> before connecting");
                    return true;
                }
                String[] endpoint = arguments.split(":", -1);
                if (endpoint.length != 2 || endpoint[0].isBlank()) {
                    LoggerUtil.INSTANCE.info("Use /connect <host>:<port>");
                    return true;
                }
                try {
                    int port = Integer.parseInt(endpoint[1]);
                    if (port < 1 || port > 65535) {
                        LoggerUtil.INSTANCE.info("Port must be between 1 and 65535");
                    } else {
                        if (connect(endpoint[0], port)) {
                            sendConnectionData();
                        }
                    }
                } catch (NumberFormatException e) {
                    LoggerUtil.INSTANCE.info("Port must be a whole number");
                }
                return true;
            case SET_NAME:
                if (isConnected()) {
                    LoggerUtil.INSTANCE.info("Disconnect before changing your name");
                } else if (arguments.isBlank()) {
                    LoggerUtil.INSTANCE.info("Use /name <name>");
                } else {
                    clientName = arguments;
                    LoggerUtil.INSTANCE.info("Name set to " + arguments);
                }
                return true;
            case USERS:
                StringBuilder builder = new StringBuilder("Known clients:\n");
                knownUsers.values().forEach(user -> builder.append(
                        user.getDisplayName() + (user.isReady() ? " [ready]" : " [not ready]")
                                + (user.isMyTurn() ? " [current turn]" : "")
                                + (user.isSubmitted() ? " [submitted]" : " [not submitted]")
                                + " points: " + user.getPoints() + "\n"));
                LoggerUtil.INSTANCE.info(builder.toString());
                return true;
            case REVERSE:
                sendReverse(arguments);
                return true;
            case READY:
                sendReady();
                return true;
            case RESET:
                sendReset();
                return true;
            case CARD:
                String[] values = arguments.trim().split("\s+");
                if (values.length != 3) {
                    LoggerUtil.INSTANCE.info("Use /card <cardId> <x> <y>");
                    return true;
                }
                try {
                    sendCardAction(Integer.parseInt(values[0]), Integer.parseInt(values[1]),
                            Integer.parseInt(values[2]));
                } catch (NumberFormatException e) {
                    LoggerUtil.INSTANCE.info("Use whole numbers for /card");
                }
                return true;
            case HAND:
                showHand();
                return true;
            case GRID:
                showGrid();
                return true;
            case DISCONNECT:
                sendDisconnect();
                return true;
            case QUIT:
                isRunning = false;
                closeServerConnection(server);
                return true;
            default:
                return false;
        }
    }

    /**
     * Send this client's READY intent; only the server confirms readiness.
     * Called by processClientCommand() for the matching slash command.
     * @throws IOException if the request cannot be written to the server
     */
    private void sendReady() throws IOException {
        sendAction(PayloadType.READY, null);
    }

    /**
     * Send this client's RESET intent; GameServer checks participant eligibility.
     * Called by processClientCommand() for the matching slash command.
     * @throws IOException if the request cannot be written to the server
     */
    private void sendReset() throws IOException {
        sendAction(PayloadType.RESET, null);
    }

    /**
     * Called during connection setup after both object streams are ready.
     * Bundles the chosen name as CLIENT_CONNECT so the server can register this client.
     *
     * @throws IOException if writing the connection request fails
     */
    private void sendConnectionData() throws IOException {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_CONNECT);
        payload.setClientName(clientName);
        sendToServer(payload);
    }

    /**
     * Called by keyboard-input handling for text that is not a slash command.
     * Uses sendAction() to bundle a MESSAGE request.
     *
     * @param message the text entered by the user
     * @throws IOException if writing to the server fails
     */
    private void sendMessage(String message) throws IOException {
        sendAction(PayloadType.MESSAGE, message);
    }

    /**
     * Called by command handling for /reverse.
     * Sends a REVERSE request; the server performs the reversal.
     *
     * @param message the text following the command
     * @throws IOException if writing to the server fails
     */
    private void sendReverse(String message) throws IOException {
        sendAction(PayloadType.REVERSE, message);
    }

    /**
     * Called by command handling for /disconnect.
     * Sends a disconnect request without ending keyboard input, allowing reconnect.
     *
     * @throws IOException if writing the request fails
     */
    private void sendDisconnect() throws IOException {
        sendAction(PayloadType.DISCONNECT, null);
    }

    /**
     * Shared bundling method for simple chat and command requests.
     * Called by command handling and the action-specific send methods.
     *
     * @param type the action the server should process
     * @param message the action's text, or null when no text is needed
     * @throws IOException if writing the request fails
     */
    private void sendAction(PayloadType type, String message) throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(type);
        payload.setMessage(message);
        sendToServer(payload);
    }

    /**
     * Shared transport method called after a send method bundles a payload.
     * Serializes writes on the current connection; when disconnected, prints
     * connection guidance and returns without sending.
     *
     * @param payload the already bundled request
     * @throws IOException if writing to an established connection fails
     */
    private synchronized void sendToServer(Payload payload) throws IOException {
        if (!isConnected()) {
            LoggerUtil.INSTANCE.info("Not connected; use /connect <host>:<port>");
            return;
        }
        out.writeObject(payload);
        out.flush();
        out.reset();
    }

    /** Receive data for this socket until it closes or reading fails. */
    private void listenToServer(Socket connection, ObjectInputStream input) {
        try (input) {
            while (!connection.isClosed()) {
                try {
                    Payload payload = (Payload) input.readObject();
                    synchronized (this) {
                        if (server == connection && !connection.isClosed()) {
                            processPayload(payload);
                        }
                    }
                } catch (ClassCastException e) {
                    LoggerUtil.INSTANCE.info("Ignoring an invalid server payload");
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            if (!connection.isClosed()) {
                LoggerUtil.INSTANCE.info("Connection ended: " + e.getMessage());
            }
        } finally {
            // A finished old receiver cannot clear a subsequently opened connection.
            closeServerConnection(connection);
        }
    }

    /** Route each incoming payload to its matching process method. */
    private void processPayload(Payload payload) {
        if (Constants.LOG_PAYLOADS && payload != null
                && payload.getPayloadType() != PayloadType.TIMER_SYNC) {
            LoggerUtil.INSTANCE.info(payload);
        }
        if (payload == null || payload.getPayloadType() == null) {
            LoggerUtil.INSTANCE.info("Ignoring an invalid server payload");
            return;
        }
        switch (payload.getPayloadType()) {
            case SERVER_SYNC:
                processServerSync((UserListPayload) payload);
                break;
            case CLIENT_ID:
                processClientId((ConnectionPayload) payload);
                break;
            case SERVER_JOIN:
                processServerJoin((ConnectionPayload) payload);
                break;
            case SERVER_LEAVE:
                processServerLeave(payload);
                break;
            case MESSAGE:
                processMessage(payload);
                break;
            case REVERSE:
                processReverse(payload);
                break;
            case GAME_PHASE_SYNC:
                processGamePhase(payload);
                break;
            case PLAYER_READY_STATUS:
                processPlayerReadyStatus((BoolPayload) payload);
                break;
            case TURN_STATUS:
                processTurnStatus((BoolPayload) payload);
                break;
            case GRID_SEED_SYNC:
                processGridSeed((GridSeedPayload) payload);
                break;
            case GRID_CELL_SYNC:
                processGridCell((GridCellPayload) payload);
                break;
            case CARD_CATALOG_SYNC:
                processCardCatalog((CardCatalogPayload) payload);
                break;
            case CARD_HAND_SYNC:
                processCardHand((CardHandPayload) payload);
                break;
            case CARD_COUNT_SYNC:
                processCardCount((IntPayload) payload);
                break;
            case TIMER_SYNC:
                processTimerSync((IntPayload) payload);
                break;
            case POINTS:
                processPoints((IntPayload) payload);
                break;
            case ROUND_SYNC:
                processRound((IntPayload) payload);
                break;
            case CURRENT_TURN:
                processCurrentTurn(payload);
                break;
            case DISCONNECT:
                processDisconnect();
                break;
            default:
                break;
        }
        if (payload.getPayloadType() != PayloadType.MESSAGE
                && payload.getPayloadType() != PayloadType.DISCONNECT) {
            publishState();
        }
    }

    /**
     * Display the authoritative phase received by processPayload().
     * @param payload the public phase update
     */
    private void processGamePhase(Payload payload) {
        phase = payload.getMessage();
        LoggerUtil.INSTANCE.info("Game phase: " + payload.getMessage());
    }

    /**
     * Apply the server's readiness update to the local User used by /users; sending /ready alone does not mark it ready.
     * @param payload the participant ID and authoritative readiness value
     */
    private void processPlayerReadyStatus(BoolPayload payload) {
        User user = knownUsers.get(payload.getClientId());
        if (user != null) {
            user.setReady(payload.getValue());
            LoggerUtil.INSTANCE.info(user.getDisplayName()
                    + (user.isReady() ? " ready" : " not ready"));
        }
    }

    /** Apply a GameServer-owned public score to the local User.
     * @param payload participant ID and authoritative total
     */
    private void processPoints(IntPayload payload) {
        User user = knownUsers.get(payload.getClientId());
        if (user != null) {
            user.setPoints(payload.getValue());
            LoggerUtil.INSTANCE.info(user.getDisplayName()
                    + " points: " + user.getPoints());
        }
    }

    /**
     * Called by processPayload() for SERVER_SYNC.
     * Adds the server's existing-user snapshot to the local map; later join and
     * leave events keep that map current.
     *
     * @param userList the user records received in the snapshot
     */
    private void processServerSync(UserListPayload userList) {
        for (User user : userList.getUsers()) {
            knownUsers.put(user.getClientId(), user);
        }
    }

    /**
     * Called by processPayload() for CLIENT_ID, the registration confirmation.
     * Records this client's server-assigned identity and displays it.
     *
     * @param confirmation the accepted name and assigned positive ID
     */
    private void processClientId(ConnectionPayload confirmation) {
        clientId = confirmation.getClientId();
        knownUsers.put(clientId, new User(clientId, confirmation.getClientName()));
        LoggerUtil.INSTANCE.info(TextFX.colorize(
                "Connected as " + confirmation.getClientName() + "#" + clientId, TextFX.Color.GREEN));
    }

    /**
     * Called by processPayload() for SERVER_JOIN.
     * Adds the new identity to the local map and displays the join notice.
     *
     * @param connection the identity of the user who joined
     */
    private void processServerJoin(ConnectionPayload connection) {
        User user = new User(connection.getClientId(), connection.getClientName());
        knownUsers.putIfAbsent(user.getClientId(), user);
        LoggerUtil.INSTANCE.info(TextFX.colorize(user.getDisplayName() + " joined", TextFX.Color.GREEN));
    }

    /**
     * Called by processPayload() for SERVER_LEAVE.
     * Removes the identity from the local map and reports it only if it was present.
     *
     * @param payload the ID of the user who left; the name is already stored locally
     */
    private void processServerLeave(Payload payload) {
        User user = knownUsers.remove(payload.getClientId());
        if (user != null) {
            publicHandCounts.remove(user.getClientId());
            LoggerUtil.INSTANCE.info(TextFX.colorize(user.getDisplayName() + " left", TextFX.Color.YELLOW));
        }
    }

    /**
     * Called by processPayload() to display a REVERSE response in cyan.
     * @param payload the completed reverse-command result
     */
    private void processReverse(Payload payload) {
        LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), TextFX.Color.CYAN));
        publishMessage(payload.getMessage());
    }

    /**
     * Called by processPayload() for MESSAGE responses.
     * Displays ordinary chat or a server-authored notice; reverse results have their own handler.
     *
     * @param payload the server message containing display text
     */
    private void processMessage(Payload payload) {
        String message = payload.getMessage();
        if (payload.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            message = TextFX.colorize(message, TextFX.Color.CYAN);
        }
        LoggerUtil.INSTANCE.info(message);
        publishMessage(payload.getMessage());
    }

    /** Store approximate remaining time for display; only Server expires turns.
     * @param payload nonnegative remaining milliseconds
     */
    private void processTimerSync(IntPayload payload) {
        int milliseconds = Math.max(0, payload.getValue());
        timerDeadlineNanos = milliseconds == 0 ? 0
                : System.nanoTime() + milliseconds * 1_000_000L;
    }

    /** Handle a server disconnect response while leaving keyboard input available. */
    private void processDisconnect() {
        closeServerConnection(server);
    }

    /** Close this socket and clear shared fields only if it is still current. */
    private synchronized void closeServerConnection(Socket connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException e) {
            LoggerUtil.INSTANCE.info("Could not close connection: " + e.getMessage());
        }
        if (server == connection) {
            server = null;
            out = null;
            clientId = Constants.DEFAULT_CLIENT_ID;
            knownUsers.clear();
            grid = null;
            cardCatalog.clear();
            hand.clear();
            publicHandCounts.clear();
            phase = "INACTIVE";
            round = 0;
            currentTurnId = 0;
            timerDeadlineNanos = 0;
            LoggerUtil.INSTANCE.info(TextFX.colorize("Disconnected", TextFX.Color.YELLOW));
            publishState();
        }
    }

    /** Read keyboard input until quit or end-of-input, then close the connection. */
    public void start() {
        try (Scanner input = new Scanner(System.in)) {
            while (isRunning && input.hasNextLine()) {
                try {
                    String text = input.nextLine();
                    if (!processClientCommand(text)) {
                        sendMessage(text);
                    }
                } catch (IOException e) {
                    LoggerUtil.INSTANCE.info("Send failed: " + e.getMessage());
                    closeServerConnection(server);
                }
            }
        } finally {
            closeServerConnection(server);
        }
    }

    /** Start the course's terminal client. */
    public static void main(String[] args) {
        Client.INSTANCE.start();
    }

    /**
     * Send a proposed card play; only GameServer validates ownership and target.
     * @param cardId card ID from this client's private hand
     * @param x target column
     * @param y target row
     * @throws IOException when the local write fails
     */
    private void sendCardAction(int cardId, int x, int y) throws IOException {
        CardActionPayload payload = new CardActionPayload();
        payload.setPayloadType(PayloadType.CARD_ACTION);
        payload.setCardId(cardId);
        payload.setX(x);
        payload.setY(y);
        sendToServer(payload);
    }

    /** Display only card IDs received privately on this connection. */
    private synchronized void showHand() {
        LoggerUtil.INSTANCE.info("Your hand: " + hand);
        for (int id : hand) {
            LoggerUtil.INSTANCE.info(String.valueOf(cardCatalog.get(id)));
        }
    }

    /** Show the latest server-confirmed grid to the terminal user. */
    private synchronized void showGrid() {
        LoggerUtil.INSTANCE.info(grid == null ? "No active grid" : grid.toString());
    }

    /** Apply a public acted flag to the local User used by /users.
     * @param payload participant ID and server-confirmed flag
     */
    private void processTurnStatus(BoolPayload payload) {
        User user = knownUsers.get(payload.getClientId());
        if (user != null) {
            user.setSubmitted(payload.getValue());
            LoggerUtil.INSTANCE.info(user.getDisplayName()
                    + (user.isSubmitted() ? " has acted" : " has not acted"));
        }
    }

    /** Rebuild the starting board or clear it on reset.
     * @param payload authoritative dimensions and seed
     */
    private void processGridSeed(GridSeedPayload payload) {
        if (payload.getWidth() == 0 && payload.getHeight() == 0) {
            grid = null;
        } else if (payload.getWidth() > 0 && payload.getHeight() > 0
                && payload.getWidth() <= 100 && payload.getHeight() <= 100) {
            grid = new Grid(payload.getWidth(), payload.getHeight(), payload.getSeed());
        }
    }

    /** Apply one authoritative cell update.
     * @param payload cell coordinate and value sent by the server
     */
    private void processGridCell(GridCellPayload payload) {
        if (grid != null && grid.contains(payload.getX(), payload.getY())) {
            grid.setValue(payload.getX(), payload.getY(), payload.getValue());
        }
    }

    /** Replace public card definitions; an empty catalog clears counts.
     * @param payload catalog sent to all clients
     */
    private void processCardCatalog(CardCatalogPayload payload) {
        cardCatalog.clear();
        if (payload.getCards().isEmpty()) {
            publicHandCounts.clear();
        }
        for (Card card : payload.getCards()) {
            cardCatalog.put(card.getId(), card);
        }
    }

    /** Replace this connection's private hand.
     * @param payload owner-only card IDs
     */
    private void processCardHand(CardHandPayload payload) {
        hand = new java.util.ArrayList<>(payload.getCardIds());
        LoggerUtil.INSTANCE.info("Your hand: " + hand);
    }

    /** Update one public hand size without receiving its card IDs.
     * @param payload participant ID and card count
     */
    private void processCardCount(IntPayload payload) {
        publicHandCounts.put(payload.getClientId(), payload.getValue());
        LoggerUtil.INSTANCE.info("Player " + payload.getClientId()
                + " has " + payload.getValue() + " cards");
    }

    /**
     * Display the server's public round number.
     * @param payload the authoritative current round
     */
    private void processRound(IntPayload payload) {
        round = payload.getValue();
        LoggerUtil.INSTANCE.info("Round " + payload.getValue());
    }

    /**
     * Apply the public turn owner to each known User for terminal and UI display.
     * An owner ID of zero clears every flag; GameServer still validates actions.
     * @param payload the owner ID selected by GameServer
     */
    private void processCurrentTurn(Payload payload) {
        currentTurnId = payload.getClientId();
        for (User user : knownUsers.values()) {
            user.setMyTurn(user.getClientId() == payload.getClientId());
        }
        LoggerUtil.INSTANCE.info("Current turn: " + payload.getClientId());
    }
}
