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
            case DISCONNECT:
                processDisconnect();
                break;
            default:
                break;
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
            LoggerUtil.INSTANCE.info(user.getDisplayName() + " left");
        }
    }

    /** Display a chat message received from the server. */
    private void processMessage(Payload payload) {
        LoggerUtil.INSTANCE.info(payload.getMessage());
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
            knownUsers.clear();
            LoggerUtil.INSTANCE.info("Disconnected");
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
