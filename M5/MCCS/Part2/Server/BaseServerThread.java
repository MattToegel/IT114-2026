package M5.MCCS.Part2.Server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import M5.MCCS.Part2.Common.Payload;
import M5.MCCS.Part2.Common.TextFX;
import M5.MCCS.Part2.Common.TextFX.Color;
import M5.MCCS.Part2.Common.User;
import M5.MCCS.Part2.Common.Constants;

/**
 * Base class the handles the underlying connection between Client and
 * Server-side
 */
public abstract class BaseServerThread extends Thread {

    protected volatile boolean isRunning = false; // control variable to stop this thread
    private ObjectOutputStream out; // exposed here for send()
    private Socket client; // communication directly to "my" client
    private User user = new User();

    public BaseServerThread() {
    }

    public BaseServerThread(Socket client) {
        this.client = client;
    }

    /**
     * Returns the status of this ServerThread
     * 
     * @return
     */
    public boolean isRunning() {
        return isRunning;
    }

    public void setClientId(long clientId) {
        this.user.setClientId(clientId);
    }

    public long getClientId() {
        // Note: We return clientId instead of threadId as we'll change this identifier
        // in the future
        return this.user.getClientId();
    }

    /**
     * Sets the client name and triggers onInitialized()
     * 
     * @param clientName
     */
    protected void setClientName(String clientName) {
        this.user.setClientName(clientName);
        onInitialized(); // triggers initialization complete callback to Server
    }

    public String getClientName() {
        return this.user.getClientName();
    }

    public String getDisplayName() {
        return this.user.getDisplayName();
    }

    /**
     * A wrapper method so we don't need to keep typing out the long/complex sysout
     * line inside
     * 
     * @param message
     */
    protected abstract void info(String message);

    /**
     * Triggered when object is fully initialized
     */
    protected abstract void onInitialized();

    /**
     * Receives a Payload and passes data to proper handler
     * 
     * @param payload
     */
    protected abstract void processPayload(Payload payload);

    /**
     * Sends a message to this client.
     *
     * @return true on success, false if the send failed (client likely
     *         disconnected)
     */
    protected boolean sendToClient(Payload payload) {
        if (!isRunning) {
            return true;
        }
        try {
            info("Sending to client: " + payload);
            out.writeObject(payload);
            out.flush();
            return true;
        } catch (IOException e) {
            info("Error sending message to client (most likely disconnected)");
            // comment this out to inspect the stack trace
            // e.printStackTrace();

            return false;
        }
    }

    /**
     * Signals this thread to stop. Sets isRunning to false and interrupts the
     * blocking readObject() call
     * so the run() loop exits and the finally block handles cleanup.
     * Call from outside this thread only (e.g. from Server).
     */
    protected void disconnect() {
        if (!isRunning) {
            return;
        }
        info("Thread being disconnected by server");
        isRunning = false;
        this.interrupt(); // unblocks readObject() if the thread is waiting on it
    }

    @Override
    public void run() {
        info("Thread starting");
        // ObjectOutputStream must be opened before ObjectInputStream on both sides.
        // Reversing the order causes a deadlock: both sides block waiting for the
        // other's stream header.
        try (ObjectOutputStream out = new ObjectOutputStream(client.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(client.getInputStream())) {
            this.out = out;
            isRunning = true;
            java.util.Timer nameCheckTimer = new java.util.Timer();
            nameCheckTimer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    if (getClientName() == null || getClientName().isBlank()) {
                        info("Client name not received. Disconnecting");
                        disconnect();
                    }
                    nameCheckTimer.cancel(); // release the timer thread regardless of outcome
                }
            }, 3000);

            while (isRunning) {
                try {
                    Payload fromClient = (Payload) in.readObject(); // blocks until client sends something
                    if (fromClient == null) {
                        throw new IOException("Connection interrupted");
                    } else {
                        info(TextFX.colorize("Received from client: " + fromClient, Color.CYAN));
                        processPayload(fromClient);
                    }
                } catch (ClassCastException | ClassNotFoundException cce) {
                    System.err.println("Error reading object as specified type: " + cce.getMessage());
                    cce.printStackTrace();
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        info("Thread interrupted during read, disconnect() was called");
                        break; // early exit
                    }
                    info("IO exception while reading from client");
                    e.printStackTrace();
                    break;
                }
            }
        } catch (Exception e) {
            info("Unexpected exception outside read loop (stream setup may have failed)");
            e.printStackTrace();
            info("Client disconnected unexpectedly");
        } finally {
            if (getClientId() != Constants.DEFAULT_CLIENT_ID) {
                Server.INSTANCE.handleDisconnect((ServerThread) this);
            }
            isRunning = false;
            info("Exited thread loop. Cleaning up connection");
            cleanup();
        }
    }

    /**
     * Cleanup method to close the connection and reset the user object
     */
    protected void cleanup() {
        info("ServerThread cleanup() start");
        try {
            client.close();
            user.reset();
            info("Closed server-side socket");
        } catch (IOException e) {
            info("Client already closed");
        }
        info("ServerThread cleanup() end");
    }
}
