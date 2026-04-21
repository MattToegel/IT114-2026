package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import Project.Client.Client;
import Project.Client.Interfaces.IGameFlowEvents;
import Project.Client.Interfaces.IPlayerEvents;
import Project.Client.Interfaces.IPlayerStatusEvents;
import Project.Common.Phase;
import Project.Common.User;

/**
 * Scrollable user roster that renders player rows and updates status badges from callbacks.
 */
public class UserListView extends JPanel implements IPlayerEvents, IPlayerStatusEvents, IGameFlowEvents {
    private final JPanel listArea = new JPanel(new GridBagLayout());
    private final HashMap<Long, UserListItem> userItemsMap = new HashMap<>();
    private Phase currentPhase = Phase.INACTIVE;

    // Scrollable user list that reacts to player/game callbacks.
    public UserListView() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Users"));

        JScrollPane scroll = new JScrollPane(listArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);

        Client.INSTANCE.registerCallback(this);
    }

    // Rebuilds rows from a full membership snapshot (join/leave safe path).
    private void rebuildList(Map<Long, User> players) {
        listArea.removeAll();
        userItemsMap.clear();

        List<User> sorted = new ArrayList<>(players.values());
        sorted.sort(Comparator.comparingLong(User::getClientId));

        for (int i = 0; i < sorted.size(); i++) {
            User user = sorted.get(i);
            UserListItem item = new UserListItem();
            item.bind(user, Client.INSTANCE.isLocalPlayer(user.getClientId()));
            // Badges appear only once gameplay starts.
            item.setStatusBadgesVisible(shouldShowBadges());

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = i;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = new Insets(0, 0, 6, 0);
            listArea.add(item, gbc);
            userItemsMap.put(user.getClientId(), item);
        }

        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = 0;
        glue.gridy = sorted.size();
        glue.weightx = 1.0;
        glue.weighty = 1.0;
        glue.fill = GridBagConstraints.BOTH;
        // Push all user rows to the top when there is extra vertical space.
        listArea.add(Box.createVerticalGlue(), glue);

        listArea.revalidate();
        listArea.repaint();
    }

    // ---- IPlayerEvents ----

    @Override
    public void onPlayersUpdated(Map<Long, User> players) {
        SwingUtilities.invokeLater(() -> {
            // Full snapshot update path (join/leave/reconnect scenarios).
            rebuildList(players);
        });
    }

    // ---- IPlayerStatusEvents ----

    @Override
    public void onLocalPlayerStatusUpdated(User localPlayer) {
        if (localPlayer == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            // Fast local-row patch avoids rebuilding whole list for frequent updates.
            UserListItem item = userItemsMap.get(localPlayer.getClientId());
            if (item != null) {
                item.setReady(localPlayer.isReady());
                item.setTurnTaken(localPlayer.isTurnTaken());
                item.setPoints(localPlayer.getPoints());
            }
        });
    }

    @Override
    public void onPlayerStatusUpdated(long playerId, boolean ready, boolean turnTaken, int points) {
        SwingUtilities.invokeLater(() -> {
            // Status changes are frequent; patch only the affected row.
            UserListItem item = userItemsMap.get(playerId);
            if (item != null) {
                item.setReady(ready);
                item.setTurnTaken(turnTaken);
                item.setPoints(points);
            }
        });
    }

    @Override
    public void onGamePhaseUpdated(Phase phase) {
        SwingUtilities.invokeLater(() -> {
            currentPhase = phase == null ? Phase.INACTIVE : phase;
            boolean showBadges = shouldShowBadges();
            for (UserListItem item : userItemsMap.values()) {
                item.setStatusBadgesVisible(showBadges);
            }
        });
    }

    @Override
    public void onCurrentTurnUpdated(long currentTurnClientId, String currentTurnDisplayName) {
        // Current-turn text belongs in the game event panel, not the roster.
    }

    @Override
    public void onAllPlayerStatusesReset() {
        SwingUtilities.invokeLater(() -> {
            for (UserListItem item : userItemsMap.values()) {
                item.setReady(false);
                item.setTurnTaken(false);
                item.setPoints(0);
            }
        });
    }

    private boolean shouldShowBadges() {
        // Badges are gameplay-specific; hide in lobby/ready-room phases.
        return currentPhase.ordinal() > Phase.READY.ordinal();
    }
}