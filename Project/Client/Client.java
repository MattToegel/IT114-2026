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
    private final java.util.Map<Long, Boolean> readyStatus = new java.util.HashMap<>();
    private final java.util.Map<Long, Boolean> turnStatus = new java.util.HashMap<>();
    private final java.util.Map<Long, Integer> points = new java.util.HashMap<>();
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

    /** Connect using the same socket path as the terminal, after choosing a name. */
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

    /** Reuse the terminal command parser for chat entered through Swing. */
    public void sendChat(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!processClientCommand(text)) {
            sendMessage(text);
        }
    }

    public void requestReady() throws IOException { sendReady(); }
    public void requestReset() throws IOException { sendReset(); }
    public void playCard(int cardId, int x, int y) throws IOException {
        sendCardAction(cardId, x, y);
    }

    /** Close only this client's socket; the server observes its departure. */
    public synchronized void disconnectClient() {
        closeServerConnection(server);
    }

    /** Keep at most one active view observer; registration sends one snapshot. */
    public synchronized void attachListener(ClientListener observer) {
        listener = java.util.Objects.requireNonNull(observer);
        observer.onState(snapshot());
    }

    /** An old view cannot detach a newer view's observer. */
    public synchronized boolean detachListener(ClientListener observer) {
        if (listener != observer) {
            return false;
        }
        listener = null;
        return true;
    }

    /** Capture this view's socket so queued cleanup cannot close a later connection. */
    public synchronized Runnable detachListenerForClose(ClientListener observer) {
        if (listener != observer) {
            return () -> {};
        }
        listener = null;
        Socket connection = server;
        return () -> closeServerConnection(connection);
    }

    /** Copy synchronized ordinary values for Swing, never raw payload objects. */
    public synchronized ClientSnapshot snapshot() {
        java.util.List<ClientSnapshot.Player> players = new java.util.ArrayList<>();
        knownUsers.values().stream()
                .sorted(java.util.Comparator.comparingLong(User::getClientId))
                .forEach(user -> players.add(new ClientSnapshot.Player(
                        user.getClientId(), user.getDisplayName(),
                        readyStatus.getOrDefault(user.getClientId(), false),
                        turnStatus.getOrDefault(user.getClientId(), false),
                        points.getOrDefault(user.getClientId(), 0),
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

    /** Notify the active observer after a state-changing payload. */
    private void publishState() {
        ClientListener observer = listener;
        if (observer != null) {
            observer.onState(snapshot());
        }
    }

    /** Forward normal chat and game explanations to the active observer. */
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
        String[] parts = text.trim().split("\\s+", 2);
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
                LoggerUtil.INSTANCE.info("Known clients:");
                knownUsers.values().forEach(user -> LoggerUtil.INSTANCE.info(user.getDisplayName()));
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
                String[] values = arguments.trim().split("\\s+");
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

    /** Send the chosen name after the streams open. */
    private void sendConnectionData() throws IOException {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_CONNECT);
        payload.setClientName(clientName);
        sendToServer(payload);
    }

    /** Send ordinary chat text to the server. */
    private void sendMessage(String message) throws IOException {
        sendAction(PayloadType.MESSAGE, message);
    }

    /** Request server-side text reversal. */
    private void sendReverse(String message) throws IOException {
        sendAction(PayloadType.REVERSE, message);
    }

    /** Request participation in the next example session. */
    private void sendReady() throws IOException {
        sendAction(PayloadType.READY, null);
    }

    /** Request a fresh readiness window. */
    private void sendReset() throws IOException {
        sendAction(PayloadType.RESET, null);
    }

    /** Request a card play at one coordinate. */
    private void sendCardAction(int cardId, int x, int y) throws IOException {
        CardActionPayload payload = new CardActionPayload();
        payload.setPayloadType(PayloadType.CARD_ACTION);
        payload.setCardId(cardId);
        payload.setX(x);
        payload.setY(y);
        sendToServer(payload);
    }

    /** Print only the private hand received on this connection. */
    private synchronized void showHand() {
        LoggerUtil.INSTANCE.info("Your hand: " + hand);
        for (int id : hand) {
            LoggerUtil.INSTANCE.info(String.valueOf(cardCatalog.get(id)));
        }
    }

    /** Print only the grid state confirmed by the server. */
    private synchronized void showGrid() {
        LoggerUtil.INSTANCE.info(grid == null ? "No active grid" : grid.toString());
    }

    /** Ask the server to close this connection while keeping keyboard input available. */
    private void sendDisconnect() throws IOException {
        sendAction(PayloadType.DISCONNECT, null);
    }

    /** Build one typed message for chat or a slash command. */
    private void sendAction(PayloadType type, String message) throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(type);
        payload.setMessage(message);
        sendToServer(payload);
    }

    /** Write through the current connection while preventing interleaved sends. */
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
                processServerLeave((ConnectionPayload) payload);
                break;
            case MESSAGE:
                processMessage(payload);
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
            case POINTS:
                processPoints((IntPayload) payload);
                break;
            case ROUND_SYNC:
                processRound((IntPayload) payload);
                break;
            case CURRENT_TURN:
                processCurrentTurn(payload);
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

    /** Store the initial list of users already connected to the server. */
    private void processServerSync(UserListPayload userList) {
        for (User user : userList.getUsers()) {
            knownUsers.put(user.getClientId(), user);
        }
    }

    /** Store the identity assigned to this connection. */
    private void processClientId(ConnectionPayload confirmation) {
        clientId = confirmation.getClientId();
        knownUsers.put(clientId, new User(clientId, confirmation.getClientName()));
        LoggerUtil.INSTANCE.info("Connected as " + confirmation.getClientName() + "#" + clientId);
    }

    /** Add the newly connected user to the local list. */
    private void processServerJoin(ConnectionPayload connection) {
        User user = new User(connection.getClientId(), connection.getClientName());
        knownUsers.putIfAbsent(user.getClientId(), user);
        LoggerUtil.INSTANCE.info(user.getDisplayName() + " joined");
    }

    /** Remove a departed user and report the departure once. */
    private void processServerLeave(ConnectionPayload connection) {
        User user = new User(connection.getClientId(), connection.getClientName());
        if (knownUsers.remove(user.getClientId()) != null) {
            readyStatus.remove(user.getClientId());
            turnStatus.remove(user.getClientId());
            points.remove(user.getClientId());
            publicHandCounts.remove(user.getClientId());
            LoggerUtil.INSTANCE.info(user.getDisplayName() + " left");
        }
    }

    /** Display a chat message received from the server. */
    private void processMessage(Payload payload) {
        LoggerUtil.INSTANCE.info(payload.getMessage());
        publishMessage(payload.getMessage());
    }

    /** Show the current game phase from the server. */
    private void processGamePhase(Payload payload) {
        phase = payload.getMessage();
        LoggerUtil.INSTANCE.info("Game phase: " + payload.getMessage());
    }

    /** Show which user has joined or left the active player set. */
    private void processPlayerReadyStatus(BoolPayload payload) {
        readyStatus.put(payload.getClientId(), payload.getValue());
        LoggerUtil.INSTANCE.info("Player " + payload.getClientId()
                + (payload.getValue() ? " ready" : " not ready"));
    }

    /** Show public turn completion. */
    private void processTurnStatus(BoolPayload payload) {
        turnStatus.put(payload.getClientId(), payload.getValue());
        LoggerUtil.INSTANCE.info("Player " + payload.getClientId()
                + (payload.getValue() ? " has acted" : " has not acted"));
    }

    /** Show a public player score. */
    private void processPoints(IntPayload payload) {
        points.put(payload.getClientId(), payload.getValue());
        LoggerUtil.INSTANCE.info("Player " + payload.getClientId()
                + " points: " + payload.getValue());
    }

    /** Show the current round number. */
    private void processRound(IntPayload payload) {
        round = payload.getValue();
        LoggerUtil.INSTANCE.info("Round " + payload.getValue());
    }

    /** Show which participant may act next. Zero means no current turn. */
    private void processCurrentTurn(Payload payload) {
        currentTurnId = payload.getClientId();
        LoggerUtil.INSTANCE.info("Current turn: " + payload.getClientId());
    }

    /** Reconstruct the seeded grid before later authoritative cell changes. */
    private void processGridSeed(GridSeedPayload payload) {
        if (payload.getWidth() == 0 && payload.getHeight() == 0) {
            grid = null;
        } else if (payload.getWidth() > 0 && payload.getHeight() > 0
                && payload.getWidth() <= 100 && payload.getHeight() <= 100) {
            grid = new Grid(payload.getWidth(), payload.getHeight(), payload.getSeed());
        }
    }

    /** Apply one cell value confirmed by the server. */
    private void processGridCell(GridCellPayload payload) {
        if (grid != null && grid.contains(payload.getX(), payload.getY())) {
            grid.setValue(payload.getX(), payload.getY(), payload.getValue());
        }
    }

    /** Replace the public card definitions for this session. */
    private void processCardCatalog(CardCatalogPayload payload) {
        cardCatalog.clear();
        if (payload.getCards().isEmpty()) {
            publicHandCounts.clear();
        }
        for (Card card : payload.getCards()) {
            cardCatalog.put(card.getId(), card);
        }
    }

    /** Replace only this client's private hand. */
    private void processCardHand(CardHandPayload payload) {
        hand = new java.util.ArrayList<>(payload.getCardIds());
        LoggerUtil.INSTANCE.info("Your hand: " + hand);
    }

    /** Store the public hand size without learning its card IDs. */
    private void processCardCount(IntPayload payload) {
        publicHandCounts.put(payload.getClientId(), payload.getValue());
        LoggerUtil.INSTANCE.info("Player " + payload.getClientId()
                + " has " + payload.getValue() + " cards");
    }

    /** Store the server's approximate remaining deadline for display only. */
    private void processTimerSync(IntPayload payload) {
        int milliseconds = Math.max(0, payload.getValue());
        timerDeadlineNanos = milliseconds == 0 ? 0
                : System.nanoTime() + milliseconds * 1_000_000L;
    }

    /** Apply the server's disconnect response to the current connection. */
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
            grid = null;
            cardCatalog.clear();
            hand.clear();
            publicHandCounts.clear();
            readyStatus.clear();
            turnStatus.clear();
            points.clear();
            knownUsers.clear();
            phase = "INACTIVE";
            round = 0;
            currentTurnId = 0;
            timerDeadlineNanos = 0;
            LoggerUtil.INSTANCE.info("Disconnected");
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
}
