package M5.MCCS.Part2.Common;

public enum PayloadType {
    CLIENT_CONNECT, // client requesting to connect to server (passing of initialization data
                    // [name])
    CLIENT_ID, // server sending client id\
    DISCONNECT, // distinct disconnect action
    REVERSE,
    MESSAGE, // sender and message
    SERVER_JOIN, // server notifying recipient of a new client joining
                 // (includes new client's id and name)
    SERVER_LEAVE, // server notifying recipient of a client leaving
                  // (includes leaving client's id and name)
    SERVER_SYNC, // server notifying recipient of existing client
                 // (includes existing client's id and name) (silently)
}
