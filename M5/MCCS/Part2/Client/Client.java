package M5.MCCS.Part2.Client;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import M5.MCCS.Part2.Common.Constants;
import M5.MCCS.Part2.Common.TextFX;
import M5.MCCS.Part2.Common.TextFX.Color;

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

    /**
     * Recognized client-side commands.
     */
    private enum Command {
        CONNECT("/connect"),
        DISCONNECT("/disconnect"),
        QUIT("/quit"),
        USERS("/users"),
        REVERSE("/reverse");

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
        if (command == null) {
            return false;
        }
        switch (command) {
            case CONNECT:
                if (isConnection(text)) {
                    // strip "/connect ", split host:port
                    String[] parts = text.trim().replaceAll(" +", " ").split(" ")[1].split(":");
                    connect(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                } else {
                    System.out.println("Invalid format. Use: /connect localhost:3000 or /connect 192.168.1.x:3000");
                }
                return true;
            case QUIT:
                close();
                return true;
            case DISCONNECT:
                sendToServer(String.join(",", Constants.COMMAND_TRIGGER, "disconnect"));
                return true;
            case USERS:
                sendToServer(String.join(",", Constants.COMMAND_TRIGGER, "users"));
                return true;
            case REVERSE:
                // strip "/reverse" prefix and send remainder as the text to reverse
                String reverseText = text.replace("/reverse", "").trim();
                sendToServer(String.join(",", Constants.COMMAND_TRIGGER, "reverse", reverseText));
                return true;
            default:
                return false;
        }
    }

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
                String fromServer = (String) in.readObject(); // blocking
                if (fromServer != null) {
                    System.out.println(TextFX.colorize(fromServer, Color.BLUE));
                } else {
                    System.out.println("Server disconnected");
                    break;
                }
            }
        } catch (ClassCastException | ClassNotFoundException cce) {
            System.err.println("Error reading object as specified type: " + cce.getMessage());
            cce.printStackTrace();
        } catch (IOException e) {
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
     * Runs in a CompletableFuture thread. Blocks on scanner.nextLine() waiting for
     * keyboard input.
     */
    private void listenToInput() {
        try (Scanner si = new Scanner(System.in)) {
            System.out.println("Waiting for input");
            while (isRunning) {
                String userInput = si.nextLine();
                if (!processClientCommand(userInput)) {
                    sendToServer(userInput);
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

    private void sendToServer(String message) throws IOException {
        if (isConnected()) {
            out.writeObject(message);
            out.flush();
        } else {
            System.out.println("Not connected to server (hint: type `/connect host:port`)");
        }
    }

    private void close() {
        isRunning = false;
        closeServerConnection();
        System.out.println("Client terminated");
    }

    private void closeServerConnection() {
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
