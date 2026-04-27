package Clicky.Server;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import Clicky.Common.LoggerUtil;

/**
 * Abstract base class for game session logic. Owns player membership and
 * lifecycle hooks. Server remains the transport/router layer.
 *
 * Subclasses should implement concrete game logic (ready check, rounds, turns,
 * elimination, etc).
 */
public abstract class BaseGameServer {

    // Cached active player set; keep in sync with ready join/leave transitions.
    protected final ConcurrentHashMap<Long, ServerThread> activePlayers = new ConcurrentHashMap<>();

    public void onClientRemoved(ServerThread client) {
        if (client == null) {
            return;
        }
        boolean wasActive = activePlayers.remove(client.getClientId()) != null;
        LoggerUtil.INSTANCE.info("[GameServer] Client removed: " + client.getDisplayName());
        if (wasActive || client.isReady()) {
            onPlayerLeft(client);
        }
    }

    /**
     * Read-only view of active players (participating in gameplay).
     */
    protected Collection<ServerThread> getActivePlayers() {
        return Collections.unmodifiableCollection(activePlayers.values());
    }

    protected int getClientCount() {
        return Server.INSTANCE.getConnectedClientCount();
    }

    protected int getActivePlayerCount() {
        return activePlayers.size();
    }

    protected boolean hasClient(ServerThread client) {
        return Server.INSTANCE.hasClient(client);
    }

    protected boolean isActivePlayer(ServerThread client) {
        return client != null && activePlayers.containsKey(client.getClientId());
    }

    protected boolean addActivePlayer(ServerThread client) {
        return client != null && activePlayers.putIfAbsent(client.getClientId(), client) == null;
    }

    protected void clearActivePlayers() {
        activePlayers.clear();
    }

    // === Lifecycle hooks for subclasses to implement ===
    /**
     * Called when a non-participating client joins the session. Useful for
     * spectator logic.
     */
    protected abstract void onSpectatorJoined(ServerThread client);

    /**
     * Called when a client joins the game session via ready check.
     */
    protected abstract void onPlayerJoined(ServerThread client);

    /**
     * Called when a client leaves the game session.
     */
    protected abstract void onPlayerLeft(ServerThread client);

    /**
     * Called when the session starts (after ready check passes).
     */
    protected abstract void onSessionStart();

    /**
     * Called when a round begins.
     */
    protected abstract void onRoundStart();

    /**
     * Called when a turn begins (if game uses turn-based logic).
     */
    protected abstract void onTurnStart();

    /**
     * Called when a turn ends.
     */
    protected abstract void onTurnEnd();

    /**
     * Called when a round ends.
     */
    protected abstract void onRoundEnd();

    /**
     * Called when the session ends.
     */
    protected abstract void onSessionEnd();
}
