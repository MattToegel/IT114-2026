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
    // TODO: Remove GUESS when replacing the temporary guessing game.
    GUESS,
    // TODO: Replace these guessing-game responses with your project's action feedback.
    GUESS_CONFIRM,
    GUESS_STATUS,
    ROUND_SYNC,
    CURRENT_TURN
}
