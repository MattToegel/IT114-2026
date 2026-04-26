package Project.Common;

public abstract class Constants {
    public static final long DEFAULT_CLIENT_ID = -1L;
    /** Sentinel client ID used to tag server-generated game event messages. */
    public static final long GAME_CLIENT_ID = -2L;
    /** Number of ready players required to start a session. */
    public static final int REQUIRE_PLAYERS = 2;
}
