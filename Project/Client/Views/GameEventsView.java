package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import Project.Client.Client;
import Project.Client.Interfaces.IConnectionEvents;
import Project.Client.Interfaces.IGameBoardEvents;
import Project.Client.Interfaces.IGameTimerEvents;
import Project.Client.Interfaces.IPlayerStatusEvents;
import Project.Common.Card;
import Project.Common.Grid;
import Project.Common.Phase;
import Project.Common.TimerType;
import Project.Common.User;

/**
 * Game event feed that displays phase/turn/timer status and styled server game messages.
 */
public class GameEventsView extends BaseMessagesView
        implements IConnectionEvents, IGameBoardEvents, IPlayerStatusEvents, IGameTimerEvents {
    private static final int OUTER_GAP = 0;
    private static final int STATUS_GAP_X = 16;
    private static final int STATUS_GAP_Y = 4;
    private static final int MESSAGE_BOTTOM_INSET = 5;
    private static final int MESSAGE_RIGHT_INSET = 5;
    private static final int WIDTH_MARGIN = 10;
    private static final int MIN_TEXT_WIDTH = 200;

    private final JLabel currentTurnLabel = new JLabel("Current Turn:");
    private final JLabel phaseValue = new JLabel("INACTIVE");
    private final JLabel turnValue = new JLabel("Unknown");
    private final JLabel timeValue = new JLabel("N/A");
    private Phase currentPhase = Phase.INACTIVE;

    // Track prior server-synced values so we only log meaningful events.
    private final Map<Long, Boolean> lastTurnTakenByPlayer = new HashMap<>();
    private final Map<Long, Integer> lastPointsByPlayer = new HashMap<>();

    // Builds the status row + scrolling event feed and subscribes for callbacks.
    public GameEventsView() {
        super(OUTER_GAP, WIDTH_MARGIN, MIN_TEXT_WIDTH, MESSAGE_BOTTOM_INSET, MESSAGE_RIGHT_INSET);
        setBorder(BorderFactory.createTitledBorder("Game Events"));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, STATUS_GAP_X, STATUS_GAP_Y));
        statusPanel.add(new JLabel("Phase:"));
        statusPanel.add(phaseValue);
        statusPanel.add(currentTurnLabel);
        statusPanel.add(turnValue);
        statusPanel.add(new JLabel("Time:"));
        statusPanel.add(timeValue);
        add(statusPanel, BorderLayout.NORTH);

        updateCurrentTurnVisibility();

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

    // ---- IConnectionEvents ------------------------------------------------------

    @Override
    public void onConnected(User localUser) {
        // Connection messages belong in ChatView, not GameEventsView.
    }

    @Override
    public void onDisconnected() {
        // Connection messages belong in ChatView, not GameEventsView.
    }

    // ---- IGameBoardEvents -------------------------------------------------------

    @Override
    public void onLocalGridUpdated(Grid localGrid) {
        // Grid sync messages are noisy and not game-event focused.
    }

    @Override
    public void onLocalHandUpdated(User localPlayer, Map<Integer, Card> cardCatalog) {
        // Hand sync messages are noisy and not game-event focused.
    }

    @Override
    public void onGamePhaseUpdated(Phase phase) {
        currentPhase = phase == null ? Phase.INACTIVE : phase;
        phaseValue.setText(currentPhase.name());
        updateCurrentTurnVisibility();

        // Phase is visible in the status field; don't duplicate it in event log.
        if (currentPhase.ordinal() <= Phase.READY.ordinal()) {
            turnValue.setText("Unknown");
        }
    }

    @Override
    public void onCurrentTurnUpdated(long currentTurnClientId, String currentTurnDisplayName) {
        turnValue.setText(currentTurnDisplayName == null ? "Unknown" : currentTurnDisplayName);
        // Turn is shown in the status bar above; no need to duplicate in the event log.
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

    // ---- IPlayerStatusEvents ----------------------------------------------------

    @Override
    public void onLocalPlayerStatusUpdated(User localPlayer) {
        // Local status updates are frequent and debug-like; use player status events
        // below for meaningful turn/points logs.
    }

    @Override
    public void onPlayerStatusUpdated(long playerId, boolean ready, boolean turnTaken, int points) {
        if (currentPhase.ordinal() < Phase.IN_PROGRESS.ordinal()) {
            // Ignore ready-room churn; only show in-game events.
            lastTurnTakenByPlayer.put(playerId, turnTaken);
            lastPointsByPlayer.put(playerId, points);
            return;
        }

        Boolean previousTurnTaken = lastTurnTakenByPlayer.put(playerId, turnTaken);
        if (turnTaken && (previousTurnTaken == null || !previousTurnTaken)) {
            appendEvent("[Turn] " + getPlayerLabel(playerId) + " completed their turn.");
        }

        Integer previousPoints = lastPointsByPlayer.put(playerId, points);
        if (previousPoints == null || previousPoints.intValue() != points) {
            appendEvent("[Points] " + getPlayerLabel(playerId) + " now has " + points + " point(s).");
        }
    }

    @Override
    public void onAllPlayerStatusesReset() {
        lastTurnTakenByPlayer.clear();
        lastPointsByPlayer.clear();
    }

    private void updateCurrentTurnVisibility() {
        boolean showCurrentTurn = currentPhase.ordinal() > Phase.READY.ordinal();
        currentTurnLabel.setVisible(showCurrentTurn);
        turnValue.setVisible(showCurrentTurn);
    }

    private String getPlayerLabel(long playerId) {
        User user = Client.INSTANCE.getKnownUsersSnapshot().get(playerId);
        if (user == null) {
            return "Player #" + playerId;
        }
        return user.getDisplayName();
    }
}