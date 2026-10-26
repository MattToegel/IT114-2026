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

    private enum Command {
        CONNECT("/connect"), DISCONNECT("/disconnect"), QUIT("/quit"),
        USERS("/users"), REVERSE("/reverse");

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
                        connect(endpoint[0], port);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Port must be a whole number");
                }
                return true;
            case USERS:
                sendToServer(Constants.COMMAND_TRIGGER + ",users");
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

    /** Send ordinary chat text to the server. */
    private void sendMessage(String message) throws IOException {
        sendToServer(message);
    }

    /** Request server-side text reversal. */
    private void sendReverse(String message) throws IOException {
        sendToServer(Constants.COMMAND_TRIGGER + ",reverse," + message);
    }

    /** Ask the server to close this connection while keeping keyboard input available. */
    private void sendDisconnect() throws IOException {
        sendToServer(Constants.COMMAND_TRIGGER + ",disconnect");
    }

    /** Write through the current connection while preventing interleaved sends. */
    private synchronized void sendToServer(String payload) throws IOException {
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
                Object received = input.readObject();
                if (received instanceof String message) {
                    synchronized (this) {
                        if (server == connection && !connection.isClosed()) {
                            System.out.println(message);
                        }
                    }
                } else {
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
