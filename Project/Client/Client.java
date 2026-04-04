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

import Project.Common.ConnectionPayload;
import Project.Common.LoggerUtil;
import Project.Common.Payload;
import Project.Common.PayloadType;
import Project.Common.TextFX;
import Project.Common.TextFX.Color;
import Project.Common.User;

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
                    if (myClientName == null || myClientName.isBlank()) {
                        LoggerUtil.INSTANCE
                                .warning(TextFX.colorize("Set your name before connecting using `/name YourName`",
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
                knownUsers.forEach((key, value) -> {
                    sb.append(TextFX.colorize(String.format("%s%s", value.getDisplayName(),
                            key == myUser.getClientId() ? " (you)" : ""), Color.CYAN));
                    sb.append("\n");
                });
                LoggerUtil.INSTANCE.info(sb.toString());
                return true;
            case REVERSE:
                // strip "/reverse" prefix and send remainder as the text to reverse
                String reverseText = text.replace("/reverse", "").trim();
                sendReverse(reverseText);
                return true;
            case SET_NAME:
                String name = text.replace("/name", "").trim();
                if (name.isBlank()) {
                    LoggerUtil.INSTANCE.severe(TextFX.colorize("Name cannot be blank", Color.RED));
                } else {
                    myUser.setClientName(name);// temporarily hold client's desired name
                    // sendConnectionData() will trigger the server-side initialization flow
                    LoggerUtil.INSTANCE.info(
                            TextFX.colorize("Name set to " + name + ".", Color.GREEN));
                }
                return true;
            default:
                return false;
        }
    }

    // Start region for send*() methods ===================================

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
            case DISCONNECT: // server acknowledged this client's disconnect command; close connection
                LoggerUtil.INSTANCE.info("Server acknowledged disconnect. Closing connection.");
                closeServerConnection();
                break;
            default:
                LoggerUtil.INSTANCE.warning("Received unhandled payload type: " + payload.getPayloadType());
        }
    }

    // Start region for process*() methods ===================================

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
