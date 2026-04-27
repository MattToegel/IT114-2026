package Clicky.Client.Views;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;

import Clicky.Client.Client;
import Clicky.Client.Interfaces.IConnectionEvents;
import Clicky.Client.Interfaces.IGameFlowEvents;
import Clicky.Client.Interfaces.IPlayerEvents;
import Clicky.Client.Interfaces.IPlayerStatusEvents;

import Clicky.Common.Constants;
import Clicky.Common.Phase;
import Clicky.Common.User;
import Clicky.Exceptions.ValidationException;

/**
 * Main gameplay panel that shows phase-aware status, cards, grid actions, and
 * game events.
 */
public class GameView extends JPanel
        implements IConnectionEvents, IPlayerEvents, IPlayerStatusEvents, IGameFlowEvents {

    private final Client client;
    private final JLabel statusLabel = new JLabel("Click the button as fast as you can");
    private final JPanel readyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final JButton readyButton = new JButton("Mark Ready");
    private final JButton clickButton = new JButton("Click Me!");

    private final GameEventsView gameEventsView = new GameEventsView();
    private final JPanel phaseContentPanel = new JPanel(new CardLayout());
    private final JPanel evaluationPanel = createCenteredPhasePanel("Evaluating round results...");

    // Card View/Panels to show
    private static final String CARD_PLAY = "PLAY";
    private static final String CARD_EVALUATION = "EVALUATION";

    private static final String CLICKS_PROPERTY = "clicks";

    // Main game panel: status line + phase-aware center content.
    // READY and IN_PROGRESS use play content; EVALUATION uses evaluation content.
    public GameView(Client client) {
        super(new BorderLayout(6, 6));
        this.client = client;

        setBorder(BorderFactory.createTitledBorder("Game"));

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        // Single-line status/instruction area shown above the board.
        status.add(statusLabel);
        readyButton.addActionListener(event -> {
            try {
                client.sendReadySignal();
            } catch (ValidationException e) {
                statusLabel.setText(e.getMessage());
            }
        });
        readyPanel.add(readyButton);
        // Add toggles for extra effects for project
        // check if person is host (first person to /ready is the host)
        status.add(readyPanel);

        clickButton.addActionListener(event -> {
            try {
                Client.INSTANCE.sendClickSignal();
            } catch (ValidationException e) {
                statusLabel.setText(e.getMessage());
            }
        });

        JPanel leftContent = new JPanel(new BorderLayout(6, 6));
        // add other visuals to leftContent (this is your game view)
        leftContent.add(clickButton, BorderLayout.CENTER);

        phaseContentPanel.add(leftContent, CARD_PLAY);
        phaseContentPanel.add(evaluationPanel, CARD_EVALUATION);

        // Vertical split keeps play area above event feed.
        JSplitPane gameSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, phaseContentPanel, gameEventsView);
        gameSplit.setResizeWeight(0.75);
        gameSplit.setDividerLocation(0.75);

        JPanel gameContent = new JPanel(new BorderLayout(6, 6));
        gameContent.add(gameSplit, BorderLayout.CENTER);

        add(status, BorderLayout.NORTH);
        add(gameContent, BorderLayout.CENTER);
        resetView();
    }

    // ---- Incoming client events ----

    @Override
    public void addNotify() {
        super.addNotify();
        client.registerCallback(this);
    }

    @Override
    public void removeNotify() {
        client.unregisterCallback(this);
        super.removeNotify();
    }

    @Override
    public void onConnected(User localUser) {
        // Connection lifecycle event keeps top status text in sync with session state.
        statusLabel.setText("Connected. Complete the ready check to join the round.");
        refreshStatusOnly();
    }

    @Override
    public void onDisconnected() {
        // Full reset on disconnect prevents stale in-progress UI state.
        resetView();
    }

    @Override
    public void onPlayersUpdated(Map<Long, User> players) {
        // Join/leave can change the ready-count denominator and numerator without a
        // per-player status event, so refresh the ready label from the latest roster.
        refreshStatusOnly();
    }

    @Override
    public void onPlayerClicksChanged(long playerId, int clicks) {
        // Click count changes affect the status text and interactivity.
        if (playerId != client.getLocalPlayerId()) {
            return; // Only update the click button for the local player.
        }
        clickButton.putClientProperty(CLICKS_PROPERTY, clicks);
        clickButton.setText(String.format("Click Me! (%d clicks)", clicks));
    }

    @Override
    public void onPlayerStatusUpdated(User user) {
        // Status updates (ready/turn/points) affect button/grid interactivity.
        refreshStateOnly();
    }

    @Override
    public void onAllPlayerStatusesReset() {
        // Round/session reset also requires control/interactivity refresh.
        refreshStateOnly();
    }

    @Override
    public void onGamePhaseUpdated(Phase phase) {
        // Phase drives which content panel is shown and which controls are active.
        refreshStateOnly();
    }

    // ---- View refresh helpers ----

    private void refreshStateOnly() {
        refreshStatusOnly();
    }

    private void refreshStatusOnly() {
        updatePhaseVisibility();
        updateReadyControls();

        switch (client.getCurrentGamePhase()) {
            case INACTIVE:
                statusLabel.setText("Use Mark Ready to join the next round.");
                break;
            case READY:
                statusLabel.setText("Waiting for ready check to complete.");
                break;
            case IN_PROGRESS:
                statusLabel.setText("Round in progress. Click the button!");
                break;
            case EVALUATION:
                statusLabel.setText("Waiting for round evaluation to complete.");
                break;
            default:
                statusLabel.setText("Waiting for game state update.");
                break;
        }

        revalidate();
        repaint();
    }

    private void updatePhaseVisibility() {
        CardLayout layout = (CardLayout) phaseContentPanel.getLayout();
        switch (client.getCurrentGamePhase()) {
            case READY:
            case IN_PROGRESS:
                layout.show(phaseContentPanel, CARD_PLAY);
                break;
            case EVALUATION:
                layout.show(phaseContentPanel, CARD_EVALUATION);
                break;
            default:
                layout.show(phaseContentPanel, CARD_PLAY);
                break;
        }
    }

    private void updateReadyControls() {
        Phase currentPhase = client.getCurrentGamePhase();
        boolean beforeGameplay = currentPhase.ordinal() <= Phase.READY.ordinal();
        boolean localReady = client.isLocalPlayerReady();
        int readyCount = client.getReadyPlayerCount();
        int readyRequired = Constants.REQUIRE_PLAYERS;

        readyPanel.setVisible(beforeGameplay);
        readyButton.setEnabled(beforeGameplay && !localReady);
        String label = localReady ? "Ready" : "Mark Ready";
        readyButton.setText(String.format("%s (%d/%d)", label, readyCount, readyRequired));
    }

    // ---- Renderers for interactive areas ----

    // ---- Local helper utilities ----

    private JPanel createCenteredPhasePanel(String message) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel(message, SwingConstants.CENTER);
        panel.add(messageLabel, BorderLayout.CENTER);
        return panel;
    }

    private void resetView() {
        // Local UI reset for disconnect/inactive states.
        statusLabel.setText("Connect to the server to receive game data.");
        refreshStatusOnly();
        clickButton.putClientProperty(CLICKS_PROPERTY, 0);
        clickButton.setText("Click Me!");
    }
}