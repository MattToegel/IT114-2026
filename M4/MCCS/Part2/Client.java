package M4.MCCS.Part2;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Scanner;

import M4.MCCS.Part2.Constants;

/** Terminal input stays on the main thread; one background thread receives messages. */
public class Client {

    private volatile Socket server;
    private ObjectOutputStream out;
    private boolean isRunning = true;
    private String clientName;
    private long clientId = Constants.DEFAULT_CLIENT_ID;
    private final java.util.concurrent.ConcurrentHashMap<Long, User> knownUsers = new java.util.concurrent.ConcurrentHashMap<>();

    private enum Command {
        CONNECT("/connect"), DISCONNECT("/disconnect"), QUIT("/quit"),
        USERS("/users"), REVERSE("/reverse"), SET_NAME("/name");

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

    /** Open streams in header-safe order and start a background receiver. */
    private synchronized boolean connect(String host, int port) {
        if (isConnected()) {
            System.out.println("Already connected; use /disconnect first");
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
            System.out.println("Connection opened");
            return true;
        } catch (IOException e) {
            System.out.println("Connection failed: " + e.getMessage());
            try {
                candidate.close();
            } catch (IOException closeError) {
                System.out.println("Could not close failed connection: " + closeError.getMessage());
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
                    System.out.println("Set a name with /name <name> before connecting");
                    return true;
                }
                String[] endpoint = arguments.split(":", -1);
                if (endpoint.length != 2 || endpoint[0].isBlank()) {
                    System.out.println("Use /connect <host>:<port>");
                    return true;
                }
                try {
                    int port = Integer.parseInt(endpoint[1]);
                    if (port < 1 || port > 65535) {
                        System.out.println("Port must be between 1 and 65535");
                    } else {
                        if (connect(endpoint[0], port)) {
                            sendConnectionData();
                        }
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Port must be a whole number");
                }
                return true;
            case SET_NAME:
                if (isConnected()) {
                    System.out.println("Disconnect before changing your name");
                } else if (arguments.isBlank()) {
                    System.out.println("Use /name <name>");
                } else {
                    clientName = arguments;
                    System.out.println("Name set to " + arguments);
                }
                return true;
            case USERS:
                System.out.println("Known clients:");
                knownUsers.values().forEach(user -> System.out.println(user.getDisplayName()));
                return true;
            case REVERSE:
                sendReverse(arguments);
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
            System.out.println("Not connected; use /connect <host>:<port>");
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
                    System.out.println("Ignoring an invalid server payload");
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            if (!connection.isClosed()) {
                System.out.println("Connection ended: " + e.getMessage());
            }
        } finally {
            // A finished old receiver cannot clear a subsequently opened connection.
            closeServerConnection(connection);
        }
    }

    /**
     * Called by the receive loop for a payload from the current server connection.
     * Dispatches by type; the selected process method displays data or updates state.
     *
     * @param payload the server response or event to process
     * @throws ClassCastException if the type and payload subclass disagree;
     *         the receive loop catches and reports it
     */
    private void processPayload(Payload payload) {
        if (payload == null || payload.getPayloadType() == null) {
            System.out.println("Ignoring an invalid server payload");
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
            case DISCONNECT:
                processDisconnect();
                break;
            default:
                break;
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
        System.out.println("Connected as " + confirmation.getClientName() + "#" + clientId);
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
        System.out.println(user.getDisplayName() + " joined");
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
            System.out.println(user.getDisplayName() + " left");
        }
    }

    /**
     * Called by processPayload() for MESSAGE responses.
     * Displays the text already prepared by the server, including reversal results.
     *
     * @param payload the server message containing display text
     */
    private void processMessage(Payload payload) {
        System.out.println(payload.getMessage());
    }

    /**
     * Called by processPayload() if the server sends a DISCONNECT response.
     * Closes this connection while leaving keyboard input available for reconnect.
     */
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
            System.out.println("Could not close connection: " + e.getMessage());
        }
        if (server == connection) {
            server = null;
            out = null;
            clientId = Constants.DEFAULT_CLIENT_ID;
            knownUsers.clear();
            System.out.println("Disconnected");
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
                    System.out.println("Send failed: " + e.getMessage());
                    closeServerConnection(server);
                }
            }
        } finally {
            closeServerConnection(server);
        }
    }

    /** Start the course's terminal client. */
    public static void main(String[] args) {
        new Client().start();
    }
}
