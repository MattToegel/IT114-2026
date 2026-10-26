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
    TURN_STATUS,
    POINTS,
    ROUND_SYNC,
    CURRENT_TURN,
    GRID_SEED_SYNC,
    GRID_CELL_SYNC,
    CARD_ACTION,
    CARD_CATALOG_SYNC,
    CARD_HAND_SYNC,
    CARD_COUNT_SYNC,
    TIMER_SYNC
}
