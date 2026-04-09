package Project.Client;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import M4.MCCS.Part1.Constants;
import Project.Common.ConnectionPayload;
import Project.Common.BoolPayload;
import Project.Common.LoggerUtil;
import Project.Common.Payload;
import Project.Common.PayloadType;
import Project.Common.Phase;
import Project.Common.PointsPayload;
import Project.Common.TextFX;
import Project.Common.TextFX.Color;
import Project.Common.User;
import Project.Common.ValidationUtils;
import Project.Exceptions.ValidationException;

/**
 * Multi-client chat client using ObjectInputStream/ObjectOutputStream.
 */
public enum Client {
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

    private Client() {
        LoggerUtil.INSTANCE.info("Client Created");
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
            server = new Socket(address, port);
            // ObjectOutputStream must be created before ObjectInputStream on both sides to
            // avoid deadlock
            out = new ObjectOutputStream(server.getOutputStream());
            in = new ObjectInputStream(server.getInputStream());
            LoggerUtil.INSTANCE.info("Client connected");
            CompletableFuture.runAsync(this::listenToServer);
        } catch (UnknownHostException e) {
            LoggerUtil.INSTANCE.severe("Unknown host: " + e.getMessage());
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("IO error: " + e.getMessage());
        }
        return isConnected();
    }

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
                knownUsers.values().forEach(c -> sb.append(String.format("%s%s Ready:%s Turn:%s Points:%s Guess:%s\n",
                        c.getDisplayName(),
                        c.getClientId() == myUser.getClientId() ? " (you)" : "",
                        c.isReady() ? "[x]" : "[ ]",
                        c.isTurnTaken() ? "[x]" : "[ ]",
                        c.getPoints(),
                        c.getGuess() == 0 ? "[?]" : c.getGuess())));
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
                sendReady();
                return true;
            // @Deprecated
            case TURN:
                String turnAction = text.replaceFirst("/turn", "").trim();
                sendTurn(turnAction);
                return true;
            case VALIDATE_CLIENT:
                isLocalValidationEnabled = !isLocalValidationEnabled;
                LoggerUtil.INSTANCE.info(TextFX.colorize(
                        "Client-side validation " + (isLocalValidationEnabled ? "enabled" : "disabled"),
                        Color.GREEN));
                return true;
            // example game action
            case GUESS:
                String guessText = text.replaceFirst("/guess", "").trim();
                sendGuess(guessText);
                return true;
            default:
                return false;
        }
    }

    // Start region for send*() methods ===================================
    /**
     * Sends a guess action to the server with the user's chosen option. <br>
     * Wraps the action in a Payload object with PayloadType.GUESS.
     *
     * @param action
     * @throws IOException
     */
    private void sendGuess(String action) throws IOException {
        String validatedTurnAction = action == null ? "" : action.trim();

        if (isLocalValidationEnabled) {
            try {
                ValidationUtils.requirePhase(currentGamePhase, Phase.IN_PROGRESS);
                ValidationUtils.requireParticipating(myUser.isReady());
                ValidationUtils.requireTurnNotTaken(myUser.isTurnTaken());
                validatedTurnAction = ValidationUtils.requireValidTurnOption(validatedTurnAction);
            } catch (ValidationException e) {
                LoggerUtil.INSTANCE.warning(TextFX.colorize(e.getMessage(), Color.YELLOW));
                return;
            }
        }

        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.GUESS);
        payload.setMessage(validatedTurnAction);
        sendToServer(payload);
    }

    /**
     * Sends a ready-check action to the server.
     */
    private void sendReady() throws IOException {
        if (isLocalValidationEnabled) {
            try {
                ValidationUtils.requirePhaseAtMost(currentGamePhase, Phase.READY);
                ValidationUtils.requireNotAlreadyReady(myUser.isReady());
            } catch (ValidationException e) {
                LoggerUtil.INSTANCE.warning(TextFX.colorize(e.getMessage(), Color.YELLOW));
                return;
            }
        }

        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.READY);
        sendToServer(payload);
    }

    /**
     * Sends a turn action to the server.
     */
    @Deprecated
    private void sendTurn(String action) throws IOException {
        String validatedTurnAction = action == null ? "" : action.trim();

        if (isLocalValidationEnabled) {
            try {
                ValidationUtils.requirePhase(currentGamePhase, Phase.IN_PROGRESS);
                ValidationUtils.requireParticipating(myUser.isReady());
                ValidationUtils.requireTurnNotTaken(myUser.isTurnTaken());
                validatedTurnAction = ValidationUtils.requireValidTurnOption(validatedTurnAction);
            } catch (ValidationException e) {
                LoggerUtil.INSTANCE.warning(TextFX.colorize(e.getMessage(), Color.YELLOW));
                return;
            }
        }

        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.TURN); // updated for this specific example
        payload.setMessage(validatedTurnAction);
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
            case DISCONNECT: // server acknowledged this client's disconnect command; close connection
                LoggerUtil.INSTANCE.info("Server acknowledged disconnect. Closing connection.");
                closeServerConnection();
                break;
            case GUESS:
                processGuessConfirmation(payload);
                break;
            case POINTS:
                processPoints(payload);
                break;
            case CURRENT_TURN:
                processCurrentTurn(payload);
                break;
            default:
                LoggerUtil.INSTANCE.warning("Received unhandled payload type: " + payload.getPayloadType());
        }
    }

    // Start region for process*() methods ===================================
    private void processCurrentTurn(Payload payload) {
        long currentTurnClientId = payload.getClientId();
        User currentTurnUser = knownUsers.get(currentTurnClientId);
        // Use local map for name lookup
        String currentTurnName = currentTurnUser != null ? currentTurnUser.getDisplayName() : "Unknown";
        LoggerUtil.INSTANCE.info(TextFX.colorize("Current turn: " + currentTurnName, Color.YELLOW));
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
            return;
        }
        User user = knownUsers.get(clientId);
        if (user == null) {
            return;
        }
        user.setPoints(points); // updated directly from trusted server
        LoggerUtil.INSTANCE.info(TextFX.colorize(
                String.format("%s now has %d points", user.getDisplayName(), points),
                Color.YELLOW));

    }

    private void processGuessConfirmation(Payload payload) {
        if (!(payload instanceof PointsPayload)) {
            LoggerUtil.INSTANCE.warning("Expected PointsPayload for GUESS confirmation, got: " + payload.getClass());
            return;
        }
        int guess = ((PointsPayload) payload).getPoints(); // abusing the points field to receive the guess back
        myUser.setGuess(guess); // update local state (example)
        LoggerUtil.INSTANCE.info(TextFX.colorize("Your guess of " + guess + " has been recorded.", Color.GREEN));
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
            return;
        }
        User user = knownUsers.get(bp.getClientId());
        if (user == null) {
            return;
        }
        user.setTurnTaken(bp.getValue());
        // Uncomment for debugging local state synchronization from server turn-status
        // payloads.
        // LoggerUtil.INSTANCE.info(TextFX.colorize(
        // String.format("[Game] %s turnTaken=%s", user.getDisplayName(),
        // bp.getValue()),
        // Color.PURPLE));
    }

    private void processReadyStatus(Payload payload) {
        if (!(payload instanceof BoolPayload)) {
            LoggerUtil.INSTANCE.warning("Expected BoolPayload for PLAYER_READY_STATUS, got: " + payload.getClass());
            return;
        }
        BoolPayload bp = (BoolPayload) payload;
        if (bp.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            // reset trigger for all users; update entire knownUsers cache
            // option 1: reset just the ready status
            // knownUsers.forEach((key, user) -> user.setReady(false));
            // option 2: reset all game-related status (cheaper)
            knownUsers.forEach((key, user) -> user.resetGameState());
            LoggerUtil.INSTANCE.info(TextFX.colorize("All users' ready status reset", Color.YELLOW));
            return;
        }

        User user = knownUsers.get(bp.getClientId());
        if (user == null) {
            return;
        }
        user.setReady(bp.getValue());
        // Uncomment for debugging local state synchronization from server ready-status
        // payloads.
        // LoggerUtil.INSTANCE.info(TextFX.colorize(
        // String.format("[Game] %s ready=%s", user.getDisplayName(), bp.getValue()),
        // Color.CYAN));
    }

    private void processGamePhaseSync(Payload payload) {
        String phaseValue = payload.getMessage();
        if (phaseValue == null || phaseValue.isBlank()) {
            LoggerUtil.INSTANCE.warning("Received invalid GAME_PHASE_SYNC payload");
            return;
        }
        try {
            currentGamePhase = Phase.valueOf(phaseValue.trim().toUpperCase());
            LoggerUtil.INSTANCE.info(TextFX.colorize("[Game] Phase: " + currentGamePhase, Color.YELLOW));
        } catch (IllegalArgumentException e) {
            LoggerUtil.INSTANCE.warning("Received unknown game phase: " + phaseValue);
        }
    }

    private void processReverse(Payload payload) {
        // reversed text response from server; print it with a different color
        LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), Color.PURPLE));
    }

    /**
     * Processes a MESSAGE payload from the server
     * 
     * @param payload
     */
    private void processMessage(Payload payload) {
        // regular chat message from another client; just print it
        LoggerUtil.INSTANCE.info(TextFX.colorize(payload.getMessage(), Color.BLUE));
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
                break;
            case SERVER_LEAVE: // client left; remove from known users and print message
                User removedUser = knownUsers.remove(incomingUserData.getClientId());
                if (removedUser != null) {
                    // only inform if we actually knew about the user
                    LoggerUtil.INSTANCE.info(TextFX.colorize(removedUser.getDisplayName() + " left", Color.RED));
                }
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
