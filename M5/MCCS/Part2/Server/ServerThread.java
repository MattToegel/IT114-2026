package M5.MCCS.Part2.Server;

import java.net.Socket;
import java.util.Objects;
import java.util.function.Consumer;

import M5.MCCS.Part2.Common.ConnectionPayload;
import M5.MCCS.Part2.Common.Constants;
import M5.MCCS.Part2.Common.Payload;
import M5.MCCS.Part2.Common.PayloadType;

/**
 * Server-side handler for one connected client.
 * Each client gets its own thread so multiple clients can be served
 * concurrently.
 */
public class ServerThread extends BaseServerThread {

    private final Consumer<ServerThread> onInitializationComplete;

    protected ServerThread(Socket myClient, Consumer<ServerThread> onInitializationComplete) {
        // validate and pass to base class constructor
        super(Objects.requireNonNull(myClient, "Client socket cannot be null"));
        // validate and assign callback
        this.onInitializationComplete = Objects.requireNonNull(onInitializationComplete, "Callback cannot be null");
        info("ServerThread created");
    }

    @Override
    protected void info(String message) {
        System.out.println(String.format("Thread[%s]: %s", this.getClientId(), message));
    }

    @Override
    protected void onInitialized() {
        if (onInitializationComplete == null) {
            info("Initialization complete but callback is null. This should not happen.");
            return;
        }
        onInitializationComplete.accept(this);
    }

    /**
     * Routes incoming text to a command handler or broadcasts it as a chat message.
     */
    @Override
    protected void processPayload(Payload incoming) {

        switch (incoming.getPayloadType()) {
            case CLIENT_CONNECT:
                processClientConnect(incoming);
                break;
            case DISCONNECT:
                processDisconnect(incoming);
                break;
            case MESSAGE:
                processMessage(incoming);
                break;
            case REVERSE:
                processReverse(incoming);
                break;
            default:
                info("Received unsupported payload type: " + incoming.getPayloadType());
        }
    }

    // Region used to hand off data to Server methods for processing
    // Start region for process*() methods ===================================
    private void processDisconnect(Payload incoming) {
        info("Processing disconnect payload");
        Server.INSTANCE.handleDisconnect(this);
    }

    private void processReverse(Payload incoming) {
        info("Processing reverse payload");
        Server.INSTANCE.handleReverseText(this, incoming.getMessage());
    }

    private void processMessage(Payload incoming) {
        info("Processing message payload");
        Server.INSTANCE.handleMessage(this, incoming.getMessage());
    }

    private void processClientConnect(Payload incoming) {
        info("Processing client connect payload");
        if (!(incoming instanceof ConnectionPayload)) {
            info("Received invalid payload for client connect: " + incoming);
            return;
        }
        if (getClientId() != Constants.DEFAULT_CLIENT_ID) {
            info("Received client connect payload but client is already initialized. Ignoring.");
            return;
        }
        // TODO: could add validation for client name here (not blank, length limit,
        // profanity filter, etc)
        setClientName(((ConnectionPayload) incoming).getClientName());
    }
    // End region for process*() methods ===================================

    // Start region for send*() methods ===================================

    /**
     * Sends a disconnect trigger to the client before disconnecting. This allows
     * the client to differentiate between a normal disconnect and a kick/timeout.
     * The client can use this information to decide whether to attempt an automatic
     * reconnect or not.
     */
    protected void sendDisconnectTrigger() {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.DISCONNECT);
        sendToClient(payload);
        disconnect();
    }

    /**
     * Sends the assigned client ID back to the client after they connect.
     * 
     * @return
     */
    protected boolean sendClientId() {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_ID);
        payload.setClientId(getClientId());
        payload.setClientName(getClientName());
        return sendToClient(payload);
    }

    /**
     * Sends a client status update to this client. Used to notify of other clients
     * joining or leaving, as well as to sync existing clients when a new client
     * joins.
     * 
     * @param clientId   the clientId of the client whose status is being updated
     * @param clientName the clientName of the client whose status is being updated
     * @param isJoin     true if this is a join event, false if it's a leave event
     * @param isSync     true if this is part of the initial sync when a new client
     *                   joins, false if it's a real-time update after the initial
     *                   sync
     * @return true if the message was sent successfully, false if the send failed
     *         (client likely disconnected)
     */
    protected boolean sendClientStatus(long clientId, String clientName, boolean isJoin, boolean isSync) {
        ConnectionPayload payload = new ConnectionPayload();
        if (isSync) {
            payload.setPayloadType(PayloadType.SERVER_SYNC);
        } else {
            payload.setPayloadType(isJoin ? PayloadType.SERVER_JOIN : PayloadType.SERVER_LEAVE);
        }
        payload.setClientId(clientId);
        payload.setClientName(clientName);
        return sendToClient(payload);
    }

    protected boolean sendMessage(String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        return sendToClient(payload);
    }
    // End region for send*() methods ===================================
}
