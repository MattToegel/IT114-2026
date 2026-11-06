package Project.Common;

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
    SERVER_SYNC,
    READY,
    RESET,
    PLAYER_READY_STATUS,
    GAME_PHASE_SYNC,
    POINTS,
    ROUND_SYNC,
    CURRENT_TURN,
    GRID_ACTION,
    TURN_STATUS,
    GRID_SEED_SYNC,
    GRID_CELL_SYNC
}
