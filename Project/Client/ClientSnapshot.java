package Project.Client;

import Project.Common.Card;
import java.util.List;

/** Immutable values a view may display without reading network payloads. */
public record ClientSnapshot(
        boolean connected, long clientId, String phase, int round,
        long currentTurnId, int remainingMillis, List<Player> players,
        List<List<Integer>> grid, List<Card> catalog, List<Integer> hand) {

    public record Player(long id, String name, boolean ready, boolean acted,
            int points, int cardCount) {
    }

    public ClientSnapshot {
        players = List.copyOf(players);
        grid = grid.stream().map(List::copyOf).toList();
        catalog = List.copyOf(catalog);
        hand = List.copyOf(hand);
    }

    /** True only when this connection owns the active turn and has a card. */
    public boolean canPlay() {
        return connected && "IN_PROGRESS".equals(phase)
                && currentTurnId == clientId && !hand.isEmpty() && !grid.isEmpty();
    }

    /** Count active participants from synchronized ready flags. */
    public long readyCount() {
        return players.stream().filter(Player::ready).count();
    }
}
