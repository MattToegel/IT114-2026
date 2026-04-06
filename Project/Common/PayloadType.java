package Project.Common;

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
    READY, // client signals ready-check participation
    TURN, // client submits a turn action
    GAME_PHASE_SYNC, // server syncs current game phase to clients
    PLAYER_READY_STATUS, // server syncs whether a user is ready
    PLAYER_TURN_STATUS, // server syncs whether a user already took a turn
}
