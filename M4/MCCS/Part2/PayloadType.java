package M4.MCCS.Part2;

/**
 * Identifies the request, response, or event carried by a Payload.
 * Client and ServerThread processPayload() methods use it to choose a handler.
 */
public enum PayloadType {
    MESSAGE,
    REVERSE,
    USERS,
    DISCONNECT,
    CLIENT_CONNECT,
    CLIENT_ID,
    SERVER_JOIN,
    SERVER_LEAVE,
    SERVER_SYNC
}
