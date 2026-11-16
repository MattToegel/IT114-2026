package Project.Common;

/** The action carried by a Payload. */
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
    GRID_ACTION,
    TURN_STATUS,
    GRID_SEED_SYNC,
    GRID_CELL_SYNC,
    POINTS,
    ROUND_SYNC,
    CURRENT_TURN
}
