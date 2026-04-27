package Clicky.Client.Views;

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

import Clicky.Client.Client;
import Clicky.Client.Interfaces.IGameFlowEvents;
import Clicky.Client.Interfaces.IPlayerEvents;
import Clicky.Client.Interfaces.IPlayerStatusEvents;
import Clicky.Common.Phase;
import Clicky.Common.User;

/**
 * Scrollable user roster that renders player rows and updates status badges
 * from callbacks.
 */
public class UserListView extends JPanel implements IPlayerEvents, IPlayerStatusEvents, IGameFlowEvents {
    private final JPanel listArea = new JPanel(new GridBagLayout());
    private final HashMap<Long, UserListItem> userItemsMap = new HashMap<>();

    // Scrollable user list that reacts to player/game callbacks.
    public UserListView() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Users"));

        JScrollPane scroll = new JScrollPane(listArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        Client.INSTANCE.registerCallback(this);
    }

    @Override
    public void removeNotify() {
        Client.INSTANCE.unregisterCallback(this);
        super.removeNotify();
    }

    // Rebuilds rows from a full membership snapshot (join/leave safe path).
    private void rebuildList(Map<Long, User> players) {
        listArea.removeAll();
        userItemsMap.clear();

        List<User> sorted = new ArrayList<>(players.values());
        // sort by clicks
        sorted.sort(Comparator.comparingInt(User::getClicks).reversed()
                .thenComparing(User::getClientName));

        // non-turn based projects
        /*
         * sorted.sort(Comparator.comparingInt(User::getPoints).reversed()
         * .thenComparing(User::getClientName));
         */

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
            refreshBadgeVisibility();
        });
    }

    @Override
    public void onPlayerPointsChanged(long playerId, int points) {
        SwingUtilities.invokeLater(() -> {
            UserListItem item = userItemsMap.get(playerId);
            if (item != null) {
                item.setPoints(points);
            }
            // inefficient but should work (better to modify the existing UI instead of
            // rebuilding, especially with frequent updates):
            // rebuildList(Client.INSTANCE.getKnownUsersSnapshot());
        });
    }

    @Override
    public void onPlayerClicksChanged(long playerId, int clicks) {
        SwingUtilities.invokeLater(() -> {
            UserListItem item = userItemsMap.get(playerId);
            if (item != null) {
                item.setClicks(clicks);
            }
            // inefficient but should work (better to modify the existing UI instead of
            // rebuilding, especially with frequent updates):
            rebuildList(Client.INSTANCE.getKnownUsersSnapshot());
        });
    }
    // ---- IPlayerStatusEvents ----

    @Override
    public void onPlayerStatusUpdated(User user) {
        if (user == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            // Status changes are frequent; patch only the affected row.
            UserListItem item = userItemsMap.get(user.getClientId());
            if (item != null) {
                applyUserStatus(item, user);
                item.setAway(user.isAway());
            }
        });
    }

    @Override
    public void onGamePhaseUpdated(Phase phase) {
        SwingUtilities.invokeLater(() -> {
            refreshBadgeVisibility();
        });
    }

    @Override
    public void onAllPlayerStatusesReset() {
        SwingUtilities.invokeLater(() -> {
            for (UserListItem item : userItemsMap.values()) {
                applyStatus(item, false, false, 0);
            }
        });
    }

    private boolean shouldShowBadges() {
        // Badges are gameplay-specific; hide in lobby/ready-room phases.
        return Client.INSTANCE.getCurrentGamePhase().ordinal() > Phase.READY.ordinal();
    }

    private void refreshBadgeVisibility() {
        boolean showBadges = shouldShowBadges();
        for (UserListItem item : userItemsMap.values()) {
            item.setStatusBadgesVisible(showBadges);
        }
    }

    private void applyUserStatus(UserListItem item, User user) {
        if (item == null || user == null) {
            return;
        }
        applyStatus(item, user.isReady(), user.isTurnTaken(), user.getPoints());
    }

    private void applyStatus(UserListItem item, boolean ready, boolean turnTaken, int points) {
        if (item == null) {
            return;
        }
        item.setReady(ready);
        item.setTurnTaken(turnTaken);
        item.setPoints(points);
    }
}