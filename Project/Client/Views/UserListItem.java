package Project.Client.Views;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import Project.Client.Client;
import Project.Common.User;

/**
 * Single user row component with display name and compact ready/turn/points badges.
 */
public class UserListItem extends JPanel {
    private static final Color NORMAL_TEXT_COLOR = Color.BLACK;
    private static final Color AWAY_TEXT_COLOR = Color.GRAY;

    private final JLabel nameLabel = new JLabel();
    // Compact badges to reduce width pressure in narrow user-list panes.
    private final JLabel readyBadge = createBadge("R", Color.GREEN, Color.BLACK);
    private final JLabel turnBadge = createBadge("T", Color.YELLOW, Color.BLACK);
    private final JLabel pointsBadge = createBadge("PTS 0", Color.CYAN, Color.BLACK);
    private final JButton awayButton = new JButton("Away");
    private final JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

    public UserListItem() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(nameLabel);

        statusRow.setOpaque(false);
        statusRow.add(readyBadge);
        statusRow.add(turnBadge);
        statusRow.add(pointsBadge);
        statusRow.setAlignmentX(LEFT_ALIGNMENT);
        add(statusRow);

        awayButton.setFont(awayButton.getFont().deriveFont(10f));
        awayButton.setVisible(false);
        awayButton.addActionListener(e -> {
            try {
                Client.INSTANCE.sendAwayToggle();
            } catch (Exception ignored) {
                // Client handles user-facing error reporting.
            }
        });
        add(awayButton);

        // Prevent rows from growing too tall while still allowing full-width expansion.
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
    }

    public void bind(User user, boolean isMe) {
        if (user == null) {
            // Defensive fallback for partially synced user rows.
            nameLabel.setText("Unknown");
            readyBadge.setVisible(false);
            turnBadge.setVisible(false);
            pointsBadge.setText("PTS 0");
            return;
        }
        String display = user.getDisplayName();
        nameLabel.setText(isMe ? display + " (you)" : display);
        setReady(user.isReady());
        setTurnTaken(user.isTurnTaken());
        setAway(user.isAway());
        setPoints(user.getPoints());
        awayButton.setVisible(isMe);
    }

    public void setReady(boolean ready) {
        readyBadge.setVisible(ready);
    }

    public void setTurnTaken(boolean turnTaken) {
        turnBadge.setVisible(turnTaken);
    }

    public void setPoints(int points) {
        pointsBadge.setText("PTS " + points);
    }

    public void setStatusBadgesVisible(boolean visible) {
        // Phase-based visibility controlled by UserListView.
        statusRow.setVisible(visible);
    }

    public void setAway(boolean away) {
        awayButton.setText(away ? "Back" : "Away");
        nameLabel.setForeground(away ? AWAY_TEXT_COLOR : NORMAL_TEXT_COLOR);
        readyBadge.setForeground(away ? AWAY_TEXT_COLOR : Color.BLACK);
        turnBadge.setForeground(away ? AWAY_TEXT_COLOR : Color.BLACK);
        pointsBadge.setForeground(away ? AWAY_TEXT_COLOR : Color.BLACK);
    }

    private static JLabel createBadge(String text, Color bg, Color fg) {
        // Shared badge style helper for readiness/turn/points chips.
        JLabel badge = new JLabel(text, SwingConstants.CENTER);
        badge.setOpaque(true);
        badge.setBackground(bg);
        badge.setForeground(fg);
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bg.darker()),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        badge.setFont(badge.getFont().deriveFont(11f));
        return badge;
    }
}