package M5.MCCS.Part2.Client;

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

import M5.MCCS.Part2.Common.ConnectionPayload;
import M5.MCCS.Part2.Common.Payload;
import M5.MCCS.Part2.Common.PayloadType;
import M5.MCCS.Part2.Common.TextFX;
import M5.MCCS.Part2.Common.TextFX.Color;
import M5.MCCS.Part2.Common.User;

/**
 * Multi-client chat client using ObjectInputStream/ObjectOutputStream.
 */
public enum Client {
    INSTANCE;

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

    /**
     * Recognized client-side commands.
     */
    private enum Command {
        CONNECT("/connect"),
        DISCONNECT("/disconnect"),
        QUIT("/quit"),
        USERS("/users"),
        REVERSE("/reverse"),
        SET_NAME("/name");

        private final String trigger;

        Command(String trigger) {
            this.trigger = trigger;
        }

        /**
         * Returns the matching Command for the given input text, or null if none
         * matched.
         */
        public static Command fromText(String text) {
            if (text == null)
                return null;
            String lower = text.toLowerCase().trim();
            for (Command c : values()) {
                if (lower.equals(c.trigger) || lower.startsWith(c.trigger + " ")) {
                    return c;
                }
            }
            return null;
        }
    }

    private Client() {
        System.out.println("Client Created");
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
            System.out.println("Client connected");
            CompletableFuture.runAsync(this::listenToServer);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
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
        System.out.println("Processing command: " + command);
        if (command == null) {
            return false;
        }
        switch (command) {
            case CONNECT:
                if (isConnection(text)) {
                    String myClientName = myUser.getClientName();
                    if (myClientName == null || myClientName.isBlank()) {
                        System.out.println(TextFX.colorize("Set your name before connecting using `/name YourName`",
                                Color.YELLOW));
                        return true;
                    }
                    // strip "/connect ", split host:port
                    String[] parts = text.trim().replaceAll(" +", " ").split(" ")[1].split(":");
                    connect(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                    sendConnectionData(myClientName); // send the desired name after connection request
                } else {
                    System.out.println("Invalid format. Use: /connect localhost:3000 or /connect 192.168.1.x:3000");
                }
                return true;
            case QUIT: // client-side termination
                close();
                return true;
            case DISCONNECT: // request to gracefully disconnect from the server
                sendDisconnect();
                return true;
            case USERS: // client-side command
                System.out.println(TextFX.colorize("Known clients:", Color.CYAN));
                knownUsers.forEach((key, value) -> {
                    System.out.println(TextFX.colorize(String.format("%s%s", value.getDisplayName(),
                            key == myUser.getClientId() ? " (you)" : ""), Color.CYAN));
                });
                return true;
            case REVERSE:
                // strip "/reverse" prefix and send remainder as the text to reverse
                String reverseText = text.replace("/reverse", "").trim();
                sendReverse(reverseText);
                return true;
            case SET_NAME:
                String name = text.replace("/name", "").trim();
                if (name.isBlank()) {
                    System.out.println(TextFX.colorize("Name cannot be blank", Color.RED));
                } else {
                    myUser.setClientName(name);// temporarily hold client's desired name
                    // sendConnectionData() will trigger the server-side initialization flow
                    System.out.println(
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
            System.out.println("Not connected to server (hint: type `/connect host:port`)");
        }
    }
    // End region for send*() methods ===================================

    public void start() throws IOException {
        System.out.println("Client starting");
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
                        System.out.println("Server disconnected");
                        break;
                    }
                } catch (ClassCastException | ClassNotFoundException cce) {
                    // recoverable: single bad payload, keep the connection alive
                    System.err.println("Error reading object as specified type: " + cce.getMessage());
                    cce.printStackTrace();
                }
            }
        } catch (IOException e) {
            // non-recoverable: stream broken, exit loop
            if (isRunning) {
                System.out.println("Connection dropped");
                e.printStackTrace();
            }
        } finally {
            closeServerConnection();
        }
        System.out.println("listenToServer thread stopped");
    }

    /**
     * Routes incoming Payloads from the server to the appropriate handler based on
     * the PayloadType.
     * 
     * @param payload
     */
    private void processPayload(Payload payload) {
        if (payload == null || payload.getPayloadType() == null) {
            System.out.println("Received invalid payload: " + payload);
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
                System.out.println("Server acknowledged disconnect. Closing connection.");
                closeServerConnection();
                break;
            default:
                System.out.println("Received unhandled payload type: " + payload.getPayloadType());
        }
    }

    // Start region for process*() methods ===================================

    private void processReverse(Payload payload) {
        // reversed text response from server; print it with a different color
        System.out.println(TextFX.colorize(payload.getMessage(), Color.PURPLE));
    }

    /**
     * Processes a MESSAGE payload from the server
     * 
     * @param payload
     */
    private void processMessage(Payload payload) {
        // regular chat message from another client; just print it
        System.out.println(TextFX.colorize(payload.getMessage(), Color.BLUE));
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
            System.out.println(String.format("Expected ConnectionPayload for %s, got: %s", payload.getPayloadType(),
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
                System.out.println(TextFX.colorize(incomingUserData.getDisplayName() + " joined", Color.GREEN));
                // intentional fall-through: SERVER_JOIN and SERVER_SYNC both need putIfAbsent
            case SERVER_SYNC: // silent sync of existing clients on initial connect; just add to known users
                knownUsers.putIfAbsent(clientId, incomingUserData);
                break;
            case SERVER_LEAVE: // client left; remove from known users and print message
                User removedUser = knownUsers.remove(incomingUserData.getClientId());
                if (removedUser != null) {
                    // only inform if we actually knew about the user
                    System.out.println(TextFX.colorize(removedUser.getDisplayName() + " left", Color.RED));
                }
                break;

            default:
                System.out.println(TextFX.colorize("Unknown status type: " + type, Color.YELLOW));
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
            System.out.println("Expected ConnectionPayload for CLIENT_ID, got: " + payload.getClass());
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
        System.out.println(TextFX.colorize("Connected", Color.GREEN));
    }
    // End region for process*() methods ===================================

    /**
     * Runs in a CompletableFuture thread. Blocks on scanner.nextLine() waiting for
     * keyboard input.
     */
    private void listenToInput() {
        try (Scanner si = new Scanner(System.in)) {
            System.out.println("Waiting for input");
            while (isRunning) {
                String userInput = si.nextLine();
                if (!processClientCommand(userInput)) {
                    sendMessage(userInput);
                }
            }
        } catch (Exception e) {
            // catches IOException from sendToServer/processClientCommand
            // and NoSuchElementException from scanner if System.in is closed
            System.out.println("Error in listenToInput(): " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("listenToInput thread stopped");
    }

    private void close() {
        isRunning = false;
        closeServerConnection();
        System.out.println("Client terminated");
    }

    private void closeServerConnection() {
        knownUsers.clear();
        myUser.reset();
        try {
            if (out != null) {
                System.out.println("Closing output stream");
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (in != null) {
                System.out.println("Closing input stream");
                in.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (server != null) {
                System.out.println("Closing connection");
                server.close();
                System.out.println("Closed socket");
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
            System.out.println("Exception from main()");
            e.printStackTrace();
        }
    }
}
