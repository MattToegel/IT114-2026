package Clicky.Client.Views;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import Clicky.Client.Client;
import Clicky.Client.Interfaces.IGameFlowEvents;
import Clicky.Client.Interfaces.IGameTimerEvents;
import Clicky.Common.Phase;
import Clicky.Common.TimerType;

/**
 * Game event feed that displays phase/turn/timer status and styled server game
 * messages.
 */
public class GameEventsView extends BaseMessagesView
        implements IGameFlowEvents, IGameTimerEvents {
    private static final int OUTER_GAP = 0;
    private static final int STATUS_GAP_X = 16;
    private static final int STATUS_GAP_Y = 4;
    private static final int MESSAGE_BOTTOM_INSET = 5;
    private static final int MESSAGE_RIGHT_INSET = 5;
    private static final int WIDTH_MARGIN = 10;
    private static final int MIN_TEXT_WIDTH = 200;

    private final JLabel phaseValue = new JLabel("INACTIVE");
    private final JLabel timeValue = new JLabel("N/A");

    // Builds the status row + scrolling event feed and subscribes for callbacks.
    public GameEventsView() {
        super(OUTER_GAP, WIDTH_MARGIN, MIN_TEXT_WIDTH, MESSAGE_BOTTOM_INSET, MESSAGE_RIGHT_INSET);
        setBorder(BorderFactory.createTitledBorder("Game Events"));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, STATUS_GAP_X, STATUS_GAP_Y));
        statusPanel.add(new JLabel("Phase:"));
        statusPanel.add(phaseValue);
        statusPanel.add(new JLabel("Time:"));
        statusPanel.add(timeValue);
        add(statusPanel, BorderLayout.NORTH);

    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Self-subscribe for all event types this panel displays.
        Client.INSTANCE.registerCallback(this);
    }

    @Override
    public void removeNotify() {
        Client.INSTANCE.unregisterCallback(this);
        super.removeNotify();
    }

    public void appendEvent(String text) {
        appendMessageHtml(formatMessageHtml(text));
    }

    // Converts plain server messages into lightweight styled HTML blocks.
    private String formatMessageHtml(String messageText) {
        // Server sends plain text; client classifies and styles locally.
        String escaped = escapeHtml(messageText);
        String lower = messageText == null ? "" : messageText.toLowerCase();

        String style = "margin:0;padding:2px 0;color:#1f2937;";
        if (lower.contains("wins with") || lower.contains("session ended in a tie")) {
            style = "margin:0;padding:4px 8px;border-left:4px solid #f59e0b;"
                    + "background:#fff7e6;color:#7c2d12;font-weight:700;";
        } else if (lower.contains("session ended") || lower.contains("round ended")) {
            style = "margin:0;padding:3px 8px;border-left:4px solid #3b82f6;"
                    + "background:#eff6ff;color:#1e3a8a;font-weight:600;";
        } else if (lower.contains("completed their turn")) {
            style = "margin:0;padding:2px 6px;border-left:3px solid #16a34a;"
                    + "background:#f0fdf4;color:#166534;";
        }

        return "<html><body style='margin:0;padding:0;'>"
                + "<p style='" + style + "'>" + escaped + "</p>"
                + "</body></html>";
    }

    private String escapeHtml(String text) {
        // Basic escaping prevents accidental HTML injection in rendered messages.
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", "<br/>");
    }

    @Override
    public void onGamePhaseUpdated(Phase currentPhase) {
        phaseValue.setText(currentPhase.name());
    }

    @Override
    public void onGameMessageReceived(String message) {
        // Game messages from the server (tagged GAME_CLIENT_ID) display here instead
        // of in the chat view.
        appendEvent(message);
    }

    @Override
    public void onGameTimerUpdated(TimerType timerType, int secondsRemaining) {
        if (timerType == null) {
            return;
        }
        String display = secondsRemaining < 0 ? "N/A" : String.format("%ds", secondsRemaining);
        timeValue.setText(display);
    }

    @Override
    public void onPlayerTurnCompleted(long playerId) {
        appendEvent("[Turn] " + getPlayerLabel(playerId) + " completed their turn.");
    }

    @Override
    public void onPlayerPointsChanged(long playerId, int points) {
        appendEvent("[Points] " + getPlayerLabel(playerId) + " now has " + points + " point(s).");
    }

    private String getPlayerLabel(long playerId) {
        return Client.INSTANCE.getUserDisplayName(playerId);
    }
}