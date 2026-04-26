package Project.Client;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Project.Client.Interfaces.IChatEvents;
import Project.Client.Interfaces.IClientCommands;
import Project.Client.Interfaces.IClientEvents;
import Project.Client.Interfaces.IConnectionEvents;
import Project.Client.Interfaces.IGameBoardEvents;
import Project.Client.Interfaces.IGameFlowEvents;
import Project.Client.Interfaces.IGameTimerEvents;
import Project.Client.Interfaces.IPlayerEvents;
import Project.Client.Interfaces.IPlayerStatusEvents;
import Project.Common.Constants;
import Project.Common.ConnectionPayload;
import Project.Common.BoolPayload;
import Project.Common.CardActionPayload;
import Project.Common.Card;
import Project.Common.CardCatalogPayload;
import Project.Common.CardHandPayload;
import Project.Common.Grid;
import Project.Common.GridCellPayload;
import Project.Common.GridSeedPayload;
import Project.Common.LoggerUtil;
import Project.Common.Payload;
import Project.Common.PayloadType;
import Project.Common.Phase;
import Project.Common.PointsPayload;
import Project.Common.TextFX;
import Project.Common.TextFX.Color;
import Project.Common.TimerPayload;
import Project.Common.TimerType;
import Project.Common.User;
import Project.Common.ValidationUtils;
import Project.Exceptions.ValidationException;

/**
 * Multi-client chat client using ObjectInputStream/ObjectOutputStream.
 */
public enum Client implements IClientCommands {
    INSTANCE;

    {
        // statically initialize the client-side LoggerUtil
        LoggerUtil.LoggerConfig config = new LoggerUtil.LoggerConfig();
        config.setFileSizeLimit(2048 * 1024); // 2MB
        config.setFileCount(1);
        config.setLogLocation("client.log");
        // Set the logger configuration
        LoggerUtil.INSTANCE.setConfig(config);
    }
    private Socket server = null;
    private ObjectOutputStream out = null;
    private ObjectInputStream in = null;
    final Pattern ipAddressPattern = Pattern
            .compile("/connect\\s+(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d{3,5})");
    final Pattern localhostPattern = Pattern.compile("/connect\\s+(localhost:\\d{3,5})");
    private volatile boolean isRunning = true;
    // local cache of users known connected users; updated by SYNC_CLIENT payloads
    // from server
    private ConcurrentHashMap<Long, User> knownUsers = new ConcurrentHashMap<>();
    private User myUser = new User(); // this client's User object; set on successful connect when server sends client
    // id
    private volatile Phase currentGamePhase = Phase.INACTIVE;
    private volatile boolean isLocalValidationEnabled = true;
    private volatile Grid localGrid;
    private volatile long localGridSeed = 0L;
    private volatile long currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
    private final List<IClientEvents> uiCallbacks = new CopyOnWriteArrayList<>();
    // Client-side card catalog: id -> Card data.
    // This lets the UI show card values while the server still remains authoritative.
    private final ConcurrentHashMap<Integer, Card> cardCatalog = new ConcurrentHashMap<>();

    private Client() {
        LoggerUtil.INSTANCE.info("Client Created");
    }

    public void registerCallback(IClientEvents callback) {
        if (callback != null && !uiCallbacks.contains(callback)) {
            uiCallbacks.add(callback);
        }
    }

    public void unregisterCallback(IClientEvents callback) {
        uiCallbacks.remove(callback);
    }
    public String getUserDisplayName(long clientId){
        User user = knownUsers.get(clientId);
        return user != null ? user.getDisplayName() : "Unknown";
    }
    
    private synchronized Map<Long, User> getKnownUsersSnapshot() {
        ConcurrentHashMap<Long, User> snapshot = new ConcurrentHashMap<>();
        knownUsers.forEach((id, user) -> {
            // Copy before exposing to callers so UI code can't mutate client-owned state.
            snapshot.put(id, User.copyOf(user));
        });
        return snapshot;
    }

    private synchronized User getMyUserSnapshot() {
        // Return a snapshot copy to avoid leaking the mutable local-user reference.
        return User.copyOf(myUser);
    }

    public synchronized long getLocalPlayerId() {
        return myUser.getClientId();
    }

    public synchronized boolean isLocalPlayer(long clientId) {
        return myUser.getClientId() == clientId;
    }

    public synchronized boolean hasLocalPlayer() {
        try {
            ValidationUtils.requireTrue(
                    myUser.getClientId() != Constants.DEFAULT_CLIENT_ID,
                    "Local player is not initialized.");
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    public synchronized boolean isLocalPlayerReady() {
        try {
            ValidationUtils.requireParticipating(myUser.isReady());
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    public synchronized int getReadyPlayerCount() {
        return (int) knownUsers.values().stream().filter(User::isReady).count();
    }

    public synchronized List<Integer> getLocalCardIdsSnapshot() {
        return new ArrayList<>(myUser.getCardIds());
    }

    public synchronized boolean isLocalPlayerTurnTaken() {
        return myUser.isTurnTaken();
    }

    public synchronized boolean canLocalPlayerPlayCardNow() {
        try {
            ValidationUtils.requireTrue(
                    myUser.getClientId() != Constants.DEFAULT_CLIENT_ID,
                    "Local player is not initialized.");
            ValidationUtils.requireParticipating(myUser.isReady());
            ValidationUtils.requirePhase(currentGamePhase, Phase.IN_PROGRESS);
            ValidationUtils.requireTurnNotTaken(myUser.isTurnTaken());
            ValidationUtils.requireCurrentPlayer(currentTurnClientId, myUser.getClientId());
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    public synchronized int getCardMod(int cardId) {
        Card card = cardCatalog.get(cardId);
        return card == null ? 0 : card.getMod();
    }

    public Phase getCurrentGamePhase() {
        // Defensive fallback ensures UI callbacks never receive a null phase.
        return currentGamePhase == null ? Phase.INACTIVE : currentGamePhase;
    }

    public long getCurrentTurnClientId() {
        return currentTurnClientId;
    }

    public synchronized Grid getLocalGridSnapshot() {
        if (localGrid == null) {
            return null;
        }
        Grid copy = new Grid();
        copy.setSize(localGrid.getWidth(), localGrid.getHeight());
        for (int x = 0; x < localGrid.getWidth(); x++) {
            for (int y = 0; y < localGrid.getHeight(); y++) {
                copy.setValue(x, y, localGrid.getValue(x, y));
            }
        }
        return copy;
    }

    public synchronized Grid getLocalGridReference() {
        return localGrid;
    }

    public synchronized Map<Integer, Card> getCardCatalogSnapshot() {
        return new LinkedHashMap<>(cardCatalog);
    }

    public synchronized String getCurrentTurnDisplayName() {
        User turnUser = knownUsers.get(currentTurnClientId);
        return turnUser != null ? turnUser.getDisplayName() : "Unknown";
    }

    private <T> void passToUiCallbacks(Class<T> type, Consumer<T> consumer) {
        try {
            for (IClientEvents callback : uiCallbacks) {
                if (type.isInstance(callback)) {
                    consumer.accept(type.cast(callback));
                }
            }
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Error passing to UI callback", e);
        }
    }

    private void emitUiConnected() {
        User localUser = getMyUserSnapshot();
        passToUiCallbacks(IConnectionEvents.class, callback -> callback.onConnected(localUser));
    }

    private void emitUiDisconnected() {
        passToUiCallbacks(IConnectionEvents.class, IConnectionEvents::onDisconnected);
    }

    private void emitUiPlayersUpdated() {
        Map<Long, User> players = getKnownUsersSnapshot();
        passToUiCallbacks(IPlayerEvents.class, callback -> callback.onPlayersUpdated(players));
    }

    private void emitUiPlayerStatusUpdated(User user) {
        if (user == null) {
            return;
        }
        passToUiCallbacks(IPlayerStatusEvents.class, callback -> callback.onPlayerStatusUpdated(user));
    }

    private void emitUiAllPlayerStatusesReset() {
        passToUiCallbacks(IPlayerStatusEvents.class, IPlayerStatusEvents::onAllPlayerStatusesReset);
    }

    private void emitUiGameEventMessage(String message) {
        passToUiCallbacks(IGameFlowEvents.class, callback -> callback.onGameMessageReceived(message));
    }

    private void emitUiChatMessage(String message) {
        passToUiCallbacks(IChatEvents.class, callback -> callback.onChatMessageReceived(message));
    }

    private void emitUiSystemMessage(String message) {
        passToUiCallbacks(IChatEvents.class, callback -> callback.onSystemMessageReceived(message));
    }

    private void emitUiGamePhaseUpdated() {
        Phase phase = getCurrentGamePhase();
        passToUiCallbacks(IGameFlowEvents.class, callback -> callback.onGamePhaseUpdated(phase));
    }

    private void emitUiCurrentTurnUpdated() {
        long currentTurnId = getCurrentTurnClientId();
        String currentTurnName = getCurrentTurnDisplayName();
        passToUiCallbacks(IGameFlowEvents.class,
                callback -> callback.onCurrentTurnUpdated(currentTurnId, currentTurnName));
    }

    private void emitUiPlayerTurnCompleted(long playerId) {
        passToUiCallbacks(IGameFlowEvents.class, callback -> callback.onPlayerTurnCompleted(playerId));
    }

    private void emitUiPlayerPointsChanged(long playerId, int points) {
        passToUiCallbacks(IGameFlowEvents.class, callback -> callback.onPlayerPointsChanged(playerId, points));
    }

    private void emitUiLocalGridUpdated() {
        Grid localGridSnapshot = getLocalGridSnapshot();
        passToUiCallbacks(IGameBoardEvents.class, callback -> callback.onLocalGridUpdated(localGridSnapshot));
    }

    private void emitUiLocalHandUpdated() {
        User localPlayer = getMyUserSnapshot();
        Map<Integer, Card> cardCatalogSnapshot = getCardCatalogSnapshot();
        passToUiCallbacks(IGameBoardEvents.class,
                callback -> callback.onLocalHandUpdated(localPlayer, cardCatalogSnapshot));
    }

    private void emitUiGameTimerUpdated(TimerType timerType, int secondsRemaining) {
        passToUiCallbacks(IGameTimerEvents.class,
                callback -> callback.onGameTimerUpdated(timerType, secondsRemaining));
    }

    public boolean isConnected() {
        if (server == null)
            return false;
        // Note: these check the client's end of the socket only; they don't detect
        // server-side failures
        return server.isConnected() && !server.isClosed()
                && !server.isInputShutdown() && !server.isOutputShutdown();
    }

    /**
     * Opens a connection to the server at the given address and port.
     * Starts listenToServer() in a background thread via CompletableFuture.
     *
     * @return true if the connection succeeded
     */
    private boolean connect(String address, int port) {
        try {
            isRunning = true;
            server = new Socket(address, port);
            // ObjectOutputStream must be created before ObjectInputStream on both sides to
            // avoid deadlock
            out = new ObjectOutputStream(server.getOutputStream());
            in = new ObjectInputStream(server.getInputStream());
            LoggerUtil.INSTANCE.info("Client connected");
            CompletableFuture.runAsync(this::listenToServer);
        } catch (UnknownHostException e) {
            LoggerUtil.INSTANCE.severe("Unknown host: " + e.getMessage());
            emitUiSystemMessage("Unknown host: " + e.getMessage());
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("IO error: " + e.getMessage());
            emitUiSystemMessage("Connection failed: " + e.getMessage());
        }
        return isConnected();
    }

    // start region IClientCommands implementation
    // Methods in this block are the public command surface used by the UI.

    @Override
    public boolean connectToServer(String host, int port, String name) {
        try {
            ValidationUtils.requireNotBlank(name, "Name cannot be blank");
            myUser.setClientName(name.trim());
            if (!connect(host, port)) {
                return false;
            }
            sendConnectionData(myUser.getClientName());
            return true;
        } catch (ValidationException e) {
            LoggerUtil.INSTANCE.warning(TextFX.colorize(e.getMessage(), Color.YELLOW));
            emitUiSystemMessage(e.getMessage());
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Failed to send connection payload: " + e.getMessage());
            emitUiSystemMessage("Failed to complete connection handshake.");
        }
        return false;
    }

    @Override
    public void disconnectFromServer() {
        try {
            sendDisconnect();
        } catch (IOException e) {
            LoggerUtil.INSTANCE.warning("Failed to send disconnect payload: " + e.getMessage());
            closeServerConnection();
        }
    }

    @Override
    public void sendChatMessage(String text) {
        if (ValidationUtils.isNullOrBlank(text)) {
            return;
        }
        try {
            sendMessage(text.trim());
        } catch (IOException e) {
            LoggerUtil.INSTANCE.warning("Failed to send chat message: " + e.getMessage());
            emitUiSystemMessage("Failed to send message.");
        }
    }

    @Override
    public void sendReadySignal() throws ValidationException {
        try {
            sendReady();
        } catch (IOException e) {
            LoggerUtil.INSTANCE.warning("Failed to send ready signal: " + e.getMessage());
            emitUiSystemMessage("Failed to send ready signal.");
        }
    }
    @Override
    public void sendAwayToggle() throws ValidationException {
        try {
            Payload payload = new Payload();
            payload.setPayloadType(PayloadType.PLAYER_AWAY_STATUS);
            sendToServer(payload);
        } catch (IOException e) {
            LoggerUtil.INSTANCE.warning("Failed to send away toggle: " + e.getMessage());
            emitUiSystemMessage("Failed to send away toggle.");
        }
    }

    @Override
    public void sendCardAction(int cardId, int x, int y) throws ValidationException {
        try {
            sendCardAction(String.format("%d %d %d", cardId, x, y));
        } catch (IOException e) {
            LoggerUtil.INSTANCE.warning("Failed to send card action: " + e.getMessage());
            emitUiSystemMessage("Failed to send card action.");
        }
    }


    
    @Override
    public void setDisplayName(String name) {
        if (ValidationUtils.isNullOrBlank(name)) {
            emitUiSystemMessage("Name cannot be blank.");
            return;
        }
        myUser.setClientName(name.trim());
    }
    // end region IClientCommands implementation

    private boolean isConnection(String text) {
        Matcher ipMatcher = ipAddressPattern.matcher(text);
        Matcher localhostMatcher = localhostPattern.matcher(text);
        return ipMatcher.matches() || localhostMatcher.matches();
    }

    /**
     * Routes user input to the appropriate action based on the Command enum.
     *
     * @return true if the input was a recognized command, false if it is a plain
     *         message
     */
    private boolean processClientCommand(String text) throws IOException {
        Command command = Command.fromText(text);
        LoggerUtil.INSTANCE.info("Processing command: " + command);
        if (command == null) {
            return false;
        }
        switch (command) {
            case CONNECT:
                if (isConnection(text)) {
                    String myClientName = myUser.getClientName();
                    try {
                        ValidationUtils.requireNotBlank(myClientName,
                                "Set your name before connecting using `/name YourName`");
                    } catch (ValidationException e) {
                        LoggerUtil.INSTANCE
                                .warning(TextFX.colorize(e.getMessage(),
                                        Color.YELLOW));
                        return true;
                    }
                    // strip "/connect ", split host:port
                    String[] parts = text.trim().replaceAll(" +", " ").split(" ")[1].split(":");
                    connect(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                    sendConnectionData(myClientName); // send the desired name after connection request
                } else {
                    LoggerUtil.INSTANCE
                            .severe("Invalid format. Use: /connect localhost:3000 or /connect 192.168.1.x:3000");
                }
                return true;
            case QUIT: // client-side termination
                close();
                return true;
            case DISCONNECT: // request to gracefully disconnect from the server
                sendDisconnect();
                return true;
            case USERS: // client-side command
                StringBuilder sb = new StringBuilder();
                sb.append("Known clients:\n");
                knownUsers.values().forEach(c -> sb.append(String.format("%s%s Ready:%s Turn:%s Points:%s\n",
                        c.getDisplayName(),
                    isLocalPlayer(c.getClientId()) ? " (you)" : "",
                        c.isReady() ? "[x]" : "[ ]",
                        c.isTurnTaken() ? "[x]" : "[ ]",
                        c.getPoints())));
                LoggerUtil.INSTANCE.info(TextFX.colorize(sb.toString().trim(), Color.CYAN));
                return true;
            case REVERSE:
                // strip "/reverse" prefix and send remainder as the text to reverse
                String reverseText = text.replace("/reverse", "").trim();
                sendReverse(reverseText);
                return true;
            case SET_NAME:
                String name = text.replace("/name", "").trim();
                try {
                    ValidationUtils.requireNotBlank(name, "Name cannot be blank");
                    myUser.setClientName(name);// temporarily hold client's desired name
                    // sendConnectionData() will trigger the server-side initialization flow
                    LoggerUtil.INSTANCE.info(
                            TextFX.colorize("Name set to " + name + ".", Color.GREEN));
                } catch (ValidationException e) {
                    LoggerUtil.INSTANCE.severe(TextFX.colorize(e.getMessage(), Color.RED));
                }
                return true;
            case READY:
                try {
                    sendReady();
                } catch (ValidationException e) {
                    LoggerUtil.INSTANCE.warning(TextFX.colorize(e.getMessage(), Color.YELLOW));
                }
                return true;
            case VALIDATE_CLIENT:
                isLocalValidationEnabled = !isLocalValidationEnabled;
                LoggerUtil.INSTANCE.info(TextFX.colorize(
                        "Client-side validation " + (isLocalValidationEnabled ? "enabled" : "disabled"),
                        Color.GREEN));
                return true;
            case GRID:
                printLocalGrid();
                return true;
            case CARD:
                String cardArgs = text.replaceFirst("/card", "").trim();
                try {
                    sendCardAction(cardArgs);
                } catch (ValidationException e) {
                    LoggerUtil.INSTANCE.warning(TextFX.colorize(e.getMessage(), Color.YELLOW));
                }
                return true;
            case HAND:
                printLocalHand();
                return true;
            default:
                return false;
        }
    }

    // start region for misc
    private String formatHandForDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < myUser.getCardIds().size(); i++) {
            int cardId = myUser.getCardIds().get(i);
            Card card = cardCatalog.get(cardId);
            int mod = card == null ? 0 : card.getMod();
            sb.append(cardId).append("(").append(mod >= 0 ? "+" : "").append(mod).append(")");
            if (i < myUser.getCardIds().size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private void printLocalHand() {
        // Client-only inspection command for debugging/UI checks.
        LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Your hand: " + formatHandForDisplay(), Color.GREEN));
    }
    private void printLocalGrid() {
        if (localGrid == null || localGrid.getWidth() <= 0 || localGrid.getHeight() <= 0) {
            LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Grid is not initialized yet.", Color.YELLOW));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Game] Local grid (%dx%d) seed=%d\n",
                localGrid.getWidth(),
                localGrid.getHeight(),
                localGridSeed));
        sb.append(localGrid.toGridString());

        LoggerUtil.INSTANCE.info(TextFX.colorize(sb.toString(), Color.CYAN));
    }
    // end region for misc

    // Start region for send*() methods ===================================
    private void sendCardAction(String args) throws IOException, ValidationException {
        String[] parts = args == null ? new String[0] : args.trim().split("\\s+");
        if (parts.length != 3) {
            LoggerUtil.INSTANCE.warning("Usage: /card <cardId> <x> <y>");
            return;
        }

        int cardId;
        int x;
        int y;
        try {
            cardId = Integer.parseInt(parts[0]);
            x = Integer.parseInt(parts[1]);
            y = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            LoggerUtil.INSTANCE.warning("Usage: /card <cardId> <x> <y>");
            return;
        }

        if (isLocalValidationEnabled) {
            ValidationUtils.requirePhase(currentGamePhase, Phase.IN_PROGRESS);
            ValidationUtils.requireParticipating(myUser.isReady());
            ValidationUtils.requireTurnNotTaken(myUser.isTurnTaken());
            ValidationUtils.requireCurrentPlayer(currentTurnClientId, myUser.getClientId());
            ValidationUtils.requireValidCardId(cardId);
            ValidationUtils.requireCardInHand(myUser.getCardIds(), cardId);
            if (localGrid != null) {
                ValidationUtils.requireInBounds(x, y, localGrid.getWidth(), localGrid.getHeight());
            }
        }

        CardActionPayload payload = new CardActionPayload();
        payload.setPayloadType(PayloadType.CARD_ACTION);
        payload.setCardId(cardId);
        payload.setX(x);
        payload.setY(y);
        sendToServer(payload);
    }

    /**
     * Sends a ready-check action to the server.
     */
    private void sendReady() throws IOException, ValidationException {
        if (isLocalValidationEnabled) {
            ValidationUtils.requirePhaseAtMost(currentGamePhase, Phase.READY);
            ValidationUtils.requireNotAlreadyReady(myUser.isReady());
        }

        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.READY);
        sendToServer(payload);
    }

    /**
     * Sends a client connect command to the server with this client's name. <br>
     * Wraps the name in a ConnectionPayload object with PayloadType.CLIENT_CONNECT.
     * 
     * @param clientName
     * @throws IOException
     */
    private void sendConnectionData(String clientName) throws IOException {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_CONNECT);
        payload.setClientName(clientName);
        sendToServer(payload);
    }

    /**
     * Sends a disconnect command to the server. <br>
     * Wraps the command in a Payload object with PayloadType.DISCONNECT.
     * 
     * @throws IOException
     */
    private void sendDisconnect() throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.DISCONNECT);
        sendToServer(payload);
    }

    /**
     * Sends a reverse command to the server with the text to reverse. <br>
     * Wraps the text in a Payload object with PayloadType.REVERSE.
     * 
     * @param text
     * @throws IOException
     */
    private void sendReverse(String text) throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.REVERSE);
        payload.setMessage(text);
        sendToServer(payload);
    }

    /**
     * Sends a chat message to the server to be broadcast to other clients. <br>
     * Wraps incoming data in Payload object with PayloadType.MESSAGE.
     * 
     * @param text
     * @throws IOException
     */
    private void sendMessage(String text) throws IOException {
        if(processClientCommand(text)){
            // if the text was a client command, don't also send it as a message
            return;
        }
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(text);
        sendToServer(payload);
    }

    private void sendToServer(Payload outgoingPayload) throws IOException {
        if (isConnected()) {
            out.writeObject(outgoingPayload);
            out.flush();
        } else {
            LoggerUtil.INSTANCE.warning("Not connected to server (hint: type `/connect host:port`)");
        }
    }
    // End region for send*() methods ===================================

    public void start() throws IOException {
        LoggerUtil.INSTANCE.info("Client starting");
        CompletableFuture<Void> inputFuture = CompletableFuture.runAsync(this::listenToInput);
        // join() attaches the async thread to the main thread so the program doesn't
        // exit prematurely
        inputFuture.join();
    }

    public void startNetworkOnly() {
        LoggerUtil.INSTANCE.info("Client network mode ready");
    }

    /**
     * Runs in a background thread. Blocks on in.readObject() waiting for server
     * messages.
     */
    private void listenToServer() {
        try {
            while (isRunning && isConnected()) {
                try {
                    Payload fromServer = (Payload) in.readObject(); // blocking
                    if (fromServer != null) {
                        processPayload(fromServer);
                    } else {
                        LoggerUtil.INSTANCE.info("Server disconnected");
                        break;
                    }
                } catch (ClassCastException | ClassNotFoundException cce) {
                    // recoverable: single bad payload, keep the connection alive
                    LoggerUtil.INSTANCE.severe("Error reading object as specified type: " + cce.getMessage());
                    cce.printStackTrace();
                }
            }
        } catch (IOException e) {
            // non-recoverable: stream broken, exit loop
            if (isRunning) {
                LoggerUtil.INSTANCE.severe("Connection dropped");
                e.printStackTrace();
            }
        } finally {
            closeServerConnection();
        }
        LoggerUtil.INSTANCE.info("listenToServer thread stopped");
    }

    /**
     * Routes incoming Payloads from the server to the appropriate handler based on
     * the PayloadType.
     * 
     * @param payload
     */
    private void processPayload(Payload payload) {
        if (payload == null || payload.getPayloadType() == null) {
            LoggerUtil.INSTANCE.warning("Received invalid payload: " + payload);
            return;
        }
        switch (payload.getPayloadType()) {
            case CLIENT_ID:
                // server is assigning this client an id; create myUser object
                processClientId(payload);
                break;
            case SERVER_JOIN:
            case SERVER_SYNC:
            case SERVER_LEAVE:
                processClientStatus(payload);
                break;
            case MESSAGE:
                processMessage(payload);
                break;
            case REVERSE:
                processReverse(payload);
                break;
            case GAME_PHASE_SYNC:
                processGamePhaseSync(payload);
                break;
            case PLAYER_READY_STATUS:
                processReadyStatus(payload);
                break;
            case PLAYER_TURN_STATUS:
                processTurnStatus(payload);
                break;
            case PLAYER_AWAY_STATUS:
                processAwayStatus(payload);
                break;
            case DISCONNECT: // server acknowledged this client's disconnect command; close connection
                LoggerUtil.INSTANCE.info("Server acknowledged disconnect. Closing connection.");
                closeServerConnection();
                break;
            case POINTS:
                processPoints(payload);
                break;
            case CURRENT_TURN:
                processCurrentTurn(payload);
                break;
            case GRID_SEED_SYNC:
                processGridSeedSync(payload);
                break;
            case GRID_CELL_SYNC:
                processGridCellSync(payload);
                break;
            case CARD_HAND_SYNC:
                processCardHandSync(payload);
                break;
            case CARD_CATALOG_SYNC:
                processCardCatalogSync(payload);
                break;
            case GAME_TIMER_SYNC:
                processGameTimerSync(payload);
                break;
            default:
                LoggerUtil.INSTANCE.warning("Received unhandled payload type: " + payload.getPayloadType());
        }
    }

    // Start region for process*() methods ===================================
    private void processCardHandSync(Payload payload) {
        if (!(payload instanceof CardHandPayload)) {
            LoggerUtil.INSTANCE.warning("Expected CardHandPayload for CARD_HAND_SYNC, got: " + payload.getClass());
            return;
        }
        CardHandPayload chp = (CardHandPayload) payload;
        if (chp.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            myUser.clearCardIds();
            LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Hand reset.", Color.YELLOW));
            return;
        }
        if (chp.getClientId() != myUser.getClientId()) {
            return;
        }

        myUser.setCardIds(chp.getCardIds());
        LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Your hand: " + formatHandForDisplay(), Color.GREEN));
        emitUiPlayerStatusUpdated(getMyUserSnapshot());
        emitUiLocalHandUpdated();
    }

    private void processCardCatalogSync(Payload payload) {
        if (!(payload instanceof CardCatalogPayload)) {
            LoggerUtil.INSTANCE
                    .warning("Expected CardCatalogPayload for CARD_CATALOG_SYNC, got: " + payload.getClass());
            return;
        }

        CardCatalogPayload ccp = (CardCatalogPayload) payload;
        // Replace local catalog with server-synced data.
        // Beginners note: this is a "cache" for display only. The server still validates
        // actions.
        cardCatalog.clear();
        for (Card card : ccp.getCards()) {
            cardCatalog.put(card.getId(), card);
        }
        LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Card catalog synced: " + cardCatalog.size() + " cards.",
                Color.GREEN));
        emitUiLocalHandUpdated();
    }

    private void processGameTimerSync(Payload payload) {
        if (!(payload instanceof TimerPayload)) {
            LoggerUtil.INSTANCE.warning("Expected TimerPayload for GAME_TIMER_SYNC, got: " + payload.getClass());
            return;
        }
        TimerPayload tp = (TimerPayload) payload;
        TimerType timerType = tp.getTimerType();
        if (timerType == null) {
            LoggerUtil.INSTANCE.warning("Received GAME_TIMER_SYNC with blank timer type.");
            return;
        }

        emitUiGameTimerUpdated(timerType, tp.getSecondsRemaining());
    }
    private void processGridSeedSync(Payload payload) {
        if (!(payload instanceof GridSeedPayload)) {
            LoggerUtil.INSTANCE.warning("Expected GridSeedPayload for GRID_SEED_SYNC, got: " + payload.getClass());
            return;
        }
        GridSeedPayload gsp = (GridSeedPayload) payload;
        clearLocalGrid();
        if (!ValidationUtils.hasValidDimensions(gsp.getWidth(), gsp.getHeight())) {
            LoggerUtil.INSTANCE.info(TextFX.colorize(
                    "[Game] Grid reset trigger received from server.",
                    Color.YELLOW));
            //clearLocalGrid();
            return;
        }

      
        localGridSeed = gsp.getSeed();
        Grid grid = new Grid();
        grid.setSize(gsp.getWidth(), gsp.getHeight());
        grid.setToRandom(localGridSeed);
        localGrid = grid;
        LoggerUtil.INSTANCE.info(TextFX.colorize(
                String.format("[Game] Grid seeded (%dx%d) with seed %d", gsp.getWidth(), gsp.getHeight(),
                        localGridSeed),
                Color.YELLOW));
        LoggerUtil.INSTANCE.info(TextFX.colorize("\n" + localGrid.toGridString(), Color.CYAN));
        emitUiLocalGridUpdated();
    }

    private void processGridCellSync(Payload payload) {
        if (!(payload instanceof GridCellPayload)) {
            LoggerUtil.INSTANCE.warning("Expected GridCellPayload for GRID_CELL_SYNC, got: " + payload.getClass());
            return;
        }
        GridCellPayload gcp = (GridCellPayload) payload;
        if (localGrid == null) {
            LoggerUtil.INSTANCE.warning("Received GRID_CELL_SYNC before local grid was initialized.");
            return;
        }
        if (!ValidationUtils.isInBounds(gcp.getX(), gcp.getY(), localGrid.getWidth(), localGrid.getHeight())) {
            LoggerUtil.INSTANCE.warning(String.format(
                    "Received out-of-bounds GRID_CELL_SYNC for (%d,%d).",
                    gcp.getX(),
                    gcp.getY()));
            return;
        }
        localGrid.setValue(gcp.getX(), gcp.getY(), gcp.getValue());
        LoggerUtil.INSTANCE.info(TextFX.colorize("\n" + localGrid.toGridString(), Color.CYAN));
        emitUiLocalGridUpdated();
    }

    private void clearLocalGrid() {
        if (localGrid != null) {
            localGrid.clear();
            localGrid = null;
        }
        localGridSeed = 0L;
    }

    private void processCurrentTurn(Payload payload) {
        currentTurnClientId = payload.getClientId();
        User currentTurnUser = knownUsers.get(currentTurnClientId);
        // Use local map for name lookup
        String currentTurnName = currentTurnUser != null ? currentTurnUser.getDisplayName() : "Unknown";
        LoggerUtil.INSTANCE.info(TextFX.colorize("Current turn: " + currentTurnName, Color.YELLOW));
        emitUiCurrentTurnUpdated();
    }

    private void processPoints(Payload payload) {
        if (!(payload instanceof PointsPayload)) {
            LoggerUtil.INSTANCE.warning("Expected PointsPayload for POINTS confirmation, got: " + payload.getClass());
            return;
        }
        long clientId = payload.getClientId();
        int points = ((PointsPayload) payload).getPoints();
        if (clientId == Constants.DEFAULT_CLIENT_ID) {
            // reset points trigger for all users (if needing to reset during a session)
            knownUsers.forEach((key, user) -> user.setPoints(0));
            LoggerUtil.INSTANCE.info(TextFX.colorize("All users' points reset", Color.YELLOW));
            emitUiAllPlayerStatusesReset();
            return;
        }
        User user = knownUsers.get(clientId);
        if (user == null) {
            return;
        }
        int previousPoints = user.getPoints();
        user.setPoints(points); // updated directly from trusted server
        if (currentGamePhase.ordinal() >= Phase.IN_PROGRESS.ordinal()) {
            // only print point updates during the game; before the game starts, points may
            // be changing frequently as users ready/unready
            LoggerUtil.INSTANCE.info(TextFX.colorize(
                    String.format("%s now has %d points", user.getDisplayName(), points),
                    Color.YELLOW));
            // Client is authoritative for status transitions; emit refined UI event once.
            if (previousPoints != points) {
                emitUiPlayerPointsChanged(user.getClientId(), points);
            }
        }
        emitUiPlayerStatusUpdated(user);
        if (isLocalPlayer(user.getClientId())) {
            emitUiPlayerStatusUpdated(getMyUserSnapshot());
        }

    }

    private void processTurnStatus(Payload payload) {
        if (!(payload instanceof BoolPayload)) {
            LoggerUtil.INSTANCE.warning("Expected BoolPayload for PLAYER_TURN_STATUS, got: " + payload.getClass());
            return;
        }
        BoolPayload bp = (BoolPayload) payload;
        // uses default client id as a reset trigger
        if (bp.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            // reset trigger for all users; update entire knownUsers cache
            knownUsers.forEach((key, user) -> user.setTurnTaken(false));
            LoggerUtil.INSTANCE.info(TextFX.colorize("All users' turn status reset", Color.YELLOW));
            emitUiAllPlayerStatusesReset();
            emitUiPlayerStatusUpdated(getMyUserSnapshot());
            return;
        }
        User user = knownUsers.get(bp.getClientId());
        if (user == null) {
            return;
        }
        boolean previousTurnTaken = user.isTurnTaken();
        user.setTurnTaken(bp.getValue());
        if (currentGamePhase.ordinal() >= Phase.IN_PROGRESS.ordinal()
                && bp.getValue()
                && !previousTurnTaken) {
            // Emit refined UI event for the false->true turn-complete transition.
            emitUiPlayerTurnCompleted(user.getClientId());
        }
        emitUiPlayerStatusUpdated(user);
        if (isLocalPlayer(user.getClientId())) {
            emitUiPlayerStatusUpdated(getMyUserSnapshot());
        }
        emitUiCurrentTurnUpdated();
        // Uncomment for debugging local state synchronization from server turn-status
        // payloads.
        // LoggerUtil.INSTANCE.info(TextFX.colorize(
        // String.format("[Game] %s turnTaken=%s", user.getDisplayName(),
        // bp.getValue()),
        // Color.PURPLE));
    }

    private void processAwayStatus(Payload payload) {
        if (!(payload instanceof BoolPayload)) {
            LoggerUtil.INSTANCE.warning("Expected BoolPayload for PLAYER_AWAY_STATUS, got: " + payload.getClass());
            return;
        }
        BoolPayload bp = (BoolPayload) payload;
        User user = knownUsers.get(bp.getClientId());
        if (user == null) {
            return;
        }
        user.setAway(bp.getValue());
        emitUiPlayerStatusUpdated(user);
    }

    private void processReadyStatus(Payload payload) {
        if (!(payload instanceof BoolPayload)) {
            LoggerUtil.INSTANCE.warning("Expected BoolPayload for PLAYER_READY_STATUS, got: " + payload.getClass());
            return;
        }
        BoolPayload bp = (BoolPayload) payload;
        // uses default client id as a reset trigger
        if (bp.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            // reset trigger for all users; update entire knownUsers cache
            // option 1: reset just the ready status
            // knownUsers.forEach((key, user) -> user.setReady(false));
            // option 2: reset all game-related status (cheaper)
            knownUsers.forEach((key, user) -> user.resetGameState());
            cardCatalog.clear();
            LoggerUtil.INSTANCE.info(TextFX.colorize("All users' ready status reset", Color.YELLOW));
            emitUiAllPlayerStatusesReset();
            emitUiPlayerStatusUpdated(getMyUserSnapshot());
            emitUiGamePhaseUpdated();
            emitUiCurrentTurnUpdated();
            emitUiLocalGridUpdated();
            emitUiLocalHandUpdated();
            return;
        }

        User user = knownUsers.get(bp.getClientId());
        if (user == null) {
            return;
        }
        user.setReady(bp.getValue());
        emitUiPlayerStatusUpdated(user);
        if (isLocalPlayer(user.getClientId())) {
            emitUiPlayerStatusUpdated(getMyUserSnapshot());
        }
        // Uncomment for debugging local state synchronization from server ready-status
        // payloads.
        // LoggerUtil.INSTANCE.info(TextFX.colorize(
        // String.format("[Game] %s ready=%s", user.getDisplayName(), bp.getValue()),
        // Color.CYAN));
    }

    private void processGamePhaseSync(Payload payload) {
        String phaseValue = payload.getMessage();
        if (ValidationUtils.isNullOrBlank(phaseValue)) {
            LoggerUtil.INSTANCE.warning("Received invalid GAME_PHASE_SYNC payload");
            return;
        }
        try {
            currentGamePhase = Phase.valueOf(phaseValue.trim().toUpperCase());
            LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Phase: " + currentGamePhase, Color.YELLOW));
            emitUiGamePhaseUpdated();
        } catch (IllegalArgumentException e) {
            LoggerUtil.INSTANCE.warning("Received unknown game phase: " + phaseValue);
        }
    }

    private void processReverse(Payload payload) {
        // reversed text response from server; print it with a different color
        LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), Color.PURPLE));
        emitUiSystemMessage(payload.getMessage());
    }

    /**
     * Processes a MESSAGE payload from the server.
     * Messages tagged with GAME_CLIENT_ID are game event messages and go to the
     * game events panel. All other messages are regular chat.
     */
    private void processMessage(Payload payload) {
        if (payload.getClientId() == Constants.GAME_CLIENT_ID) {
            // Server-generated game event: route to game events panel, not chat.
            LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), Color.YELLOW));
            emitUiGameEventMessage(payload.getMessage());
        } else {
            // Regular user chat message.
            LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), Color.BLUE));
            emitUiChatMessage(payload.getMessage());
        }
    }

    /**
     * Processes SERVER_JOIN, SERVER_LEAVE, and SERVER_SYNC payloads, which all
     * share the same format.
     * Updates the knownUsers cache and prints join/leave messages as appropriate.
     * 
     * @param payload
     */
    private void processClientStatus(Payload payload) {
        if (!(payload instanceof ConnectionPayload)) {
            LoggerUtil.INSTANCE
                    .warning(String.format("Expected ConnectionPayload for %s, got: %s", payload.getPayloadType(),
                            payload.getClass()));
            return;
        }
        // SERVER_JOIN, SERVER_LEAVE, and SERVER_SYNC all use the same payload type and
        // format
        PayloadType type = payload.getPayloadType();
        long clientId = payload.getClientId();
        String clientName = ((ConnectionPayload) payload).getClientName();
        // temp user reference to avoid repeated code; will be added/removed from
        // knownUsers cache as needed
        User incomingUserData = new User(clientId, clientName);
        switch (type) {
            case SERVER_JOIN: // new client joined; print join message then fall through to add to knownUsers
                LoggerUtil.INSTANCE.info(TextFX.colorize(incomingUserData.getDisplayName() + " joined", Color.GREEN));
                // intentional fall-through: SERVER_JOIN and SERVER_SYNC both need putIfAbsent
            case SERVER_SYNC: // silent sync of existing clients on initial connect; just add to known users
                knownUsers.putIfAbsent(clientId, incomingUserData);
                emitUiPlayersUpdated();
                break;
            case SERVER_LEAVE: // client left; remove from known users and print message
                User removedUser = knownUsers.remove(incomingUserData.getClientId());
                if (removedUser != null) {
                    // only inform if we actually knew about the user
                    LoggerUtil.INSTANCE.info(TextFX.colorize(removedUser.getDisplayName() + " left", Color.RED));
                }
                emitUiPlayersUpdated();
                break;

            default:
                LoggerUtil.INSTANCE.warning(TextFX.colorize("Unknown status type: " + type, Color.YELLOW));
                break;
        }
    }

    /**
     * Processes a CLIENT_ID payload from the server, which assigns this client its
     * unique id and name.
     * 
     * @param payload
     */
    private void processClientId(Payload payload) {
        if (!(payload instanceof ConnectionPayload)) {
            LoggerUtil.INSTANCE.warning("Expected ConnectionPayload for CLIENT_ID, got: " + payload.getClass());
            return;
        }
        // extract data
        long assignedId = payload.getClientId();
        String clientName = ((ConnectionPayload) payload).getClientName();
        // create my users
        myUser.setClientId(assignedId);
        myUser.setClientName(clientName);
        // add to known users cache
        knownUsers.put(assignedId, myUser);
        LoggerUtil.INSTANCE.info(TextFX.colorize("Connected", Color.GREEN));
        emitUiConnected();
        emitUiPlayerStatusUpdated(getMyUserSnapshot());
        emitUiPlayersUpdated();
    }
    // End region for process*() methods ===================================

    /**
     * Runs in a CompletableFuture thread. Blocks on scanner.nextLine() waiting for
     * keyboard input.
     */
    private void listenToInput() {
        try (Scanner si = new Scanner(System.in)) {
            LoggerUtil.INSTANCE.info("Waiting for input");
            while (isRunning) {
                String userInput = si.nextLine();
                if (!processClientCommand(userInput)) {
                    sendMessage(userInput);
                }
            }
        } catch (Exception e) {
            // catches IOException from sendToServer/processClientCommand
            // and NoSuchElementException from scanner if System.in is closed
            LoggerUtil.INSTANCE.severe("Error in listenToInput(): " + e.getMessage());
            e.printStackTrace();
        }
        LoggerUtil.INSTANCE.info("listenToInput thread stopped");
    }

    private void close() {
        isRunning = false;
        closeServerConnection();
        LoggerUtil.INSTANCE.info("Client terminated");
    }

    private void closeServerConnection() {
        knownUsers.clear();
        myUser.reset();
        currentGamePhase = Phase.INACTIVE;
        currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
        clearLocalGrid();
        try {
            if (out != null) {
                LoggerUtil.INSTANCE.info("Closing output stream");
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (in != null) {
                LoggerUtil.INSTANCE.info("Closing input stream");
                in.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (server != null) {
                LoggerUtil.INSTANCE.info("Closing connection");
                server.close();
                LoggerUtil.INSTANCE.info("Closed socket");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        emitUiDisconnected();
        emitUiPlayersUpdated();
        emitUiGamePhaseUpdated();
        emitUiCurrentTurnUpdated();
        emitUiLocalGridUpdated();
        emitUiLocalHandUpdated();
    }

    public static void main(String[] args) {
        Client client = Client.INSTANCE;
        try {
            client.start();
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Exception from main()");
            e.printStackTrace();
        }
    }
}
