package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;

import Project.Client.Client;
import Project.Client.Interfaces.IConnectionEvents;
import Project.Client.Interfaces.IGameBoardEvents;
import Project.Client.Interfaces.IPlayerEvents;
import Project.Client.Interfaces.IPlayerStatusEvents;
import Project.Common.Card;
import Project.Common.Constants;
import Project.Common.Grid;
import Project.Common.Phase;
import Project.Common.User;
import Project.Exceptions.ValidationException;

/**
 * Main gameplay panel that shows phase-aware status, cards, grid actions, and game events.
 */
public class GameView extends JPanel implements IConnectionEvents, IPlayerEvents, IPlayerStatusEvents, IGameBoardEvents {
    private static final Color GRID_CHANGED_BG = new Color(255, 244, 179);
    private static final Color GRID_CHANGED_ODD_BG = new Color(255, 220, 130);
    private static final Color GRID_ODD_TEXT = new Color(170, 35, 35);
    private static final Color CARD_SELECTED_BG = GRID_CHANGED_BG;
    private static final Color CARD_SELECTED_BORDER = GRID_CHANGED_ODD_BG;
    private static final Color CARD_UNSELECTED_BORDER = new Color(140, 140, 140);

    private final Client client;
    private final JLabel selectionLabel = new JLabel("Select a card, then select a grid cell.");
    private final JPanel readyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final JButton readyButton = new JButton("Mark Ready");
    private final JPanel cardsPanel = new JPanel(new GridLayout(1, 0, 6, 6));
    private final JPanel gridPanel = new JPanel();
    private final GameEventsView gameEventsView = new GameEventsView();
    private final JPanel phaseContentPanel = new JPanel(new CardLayout());
    private final JPanel evaluationPanel = createCenteredPhasePanel("Evaluating round results...");
    private static final String CARD_PLAY = "PLAY";
    private static final String CARD_EVALUATION = "EVALUATION";
    private static final String CARD_ID_KEY = "cardId";
    private static final String CARD_BASE_LABEL_KEY = "cardBaseLabel";
    private static final String CARD_DEFAULT_BG_KEY = "cardDefaultBg";
    // Per-button last synced value.
    private static final String CELL_VALUE_KEY = "cellValue";
    // Per-button changed flag for current sync pass.
    private static final String CELL_CHANGED_KEY = "cellChanged";

    private Integer selectedCardId;
    private JButton[][] gridButtons = new JButton[0][0];
    private Color defaultGridCellBg;
    private Color defaultGridCellFg;
    private Font defaultGridCellFont;
    private int gridWidth;
    private int gridHeight;
    private long lastTurnOwnerId = Constants.DEFAULT_CLIENT_ID;

    // Main game panel: status line + phase-aware center content.
    // READY and IN_PROGRESS use play content; EVALUATION uses evaluation content.
    public GameView(Client client) {
        super(new BorderLayout(6, 6));
        this.client = client;

        setBorder(BorderFactory.createTitledBorder("Game"));

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        // Single-line status/instruction area shown above the board.
        status.add(selectionLabel);
        readyButton.addActionListener(event -> {
            try {
                client.sendReadySignal();
            } catch (ValidationException e) {
                selectionLabel.setText(e.getMessage());
            }
        });
        readyPanel.add(readyButton);
        status.add(readyPanel);

        cardsPanel.setBorder(BorderFactory.createTitledBorder("Cards"));

        JPanel gridContainer = new JPanel(new BorderLayout(4, 4));
        gridContainer.setBorder(BorderFactory.createTitledBorder("Grid"));
        gridContainer.add(gridPanel, BorderLayout.CENTER);

        // Cards are horizontal with overflow scroll so each card stays readable.
        JScrollPane cardsScroll = new JScrollPane(cardsPanel,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel leftContent = new JPanel(new BorderLayout(6, 6));
        leftContent.add(gridContainer, BorderLayout.CENTER);
        leftContent.add(cardsScroll, BorderLayout.SOUTH);

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
        selectionLabel.setText("Connected. Complete the ready check to join the round.");
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

    @Override
    public void onCurrentTurnUpdated(long currentTurnClientId, String currentTurnDisplayName) {
        // Turn handover is used as the boundary for clearing previous action highlights.
        if (lastTurnOwnerId != currentTurnClientId) {
            clearGridChangeHighlights();
            lastTurnOwnerId = currentTurnClientId;
        }
        // Turn ownership affects whether local actions should be enabled.
        refreshStateOnly();
    }

    @Override
    public void onLocalGridUpdated(Grid localGrid) {
        // Grid sync updates values/cell controls without rebuilding card rows.
        refreshStatusOnly();
        syncGridFromModel();
    }

    @Override
    public void onLocalHandUpdated(User localPlayer, Map<Integer, Card> cardCatalog) {
        // Hand sync requires rebuilding card rows from the latest local hand snapshot.
        refreshForHandUpdate();
    }

    // ---- View refresh helpers ----

    private void refreshStateOnly() {
        refreshStatusOnly();
        updateCardInteractivity();
        updateGridInteractivity();
    }

    private void refreshForHandUpdate() {
        refreshStatusOnly();
        rebuildCards();
        updateGridInteractivity();
    }

    private void refreshStatusOnly() {
        updatePhaseVisibility();
        updateReadyControls();

        switch (client.getCurrentGamePhase()) {
            case INACTIVE:
                selectionLabel.setText("Use Mark Ready to join the next round.");
                break;
            case READY:
                selectionLabel.setText("Waiting for ready check to complete.");
                break;
            case IN_PROGRESS:
                if (client.isLocalPlayerReady()) {
                    selectionLabel.setText("Select a card, then click a grid cell.");
                } else {
                    selectionLabel.setText("Waiting for your turn.");
                }
                break;
            case EVALUATION:
                selectionLabel.setText("Waiting for round evaluation to complete.");
                break;
            default:
                selectionLabel.setText("Waiting for game state update.");
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

    private void rebuildCards() {
        boolean canPlay = client.canLocalPlayerPlayCardNow();
        cardsPanel.removeAll();
        List<Integer> cardIds = client.getLocalCardIdsSnapshot();
        cardIds.sort(Comparator.naturalOrder());

        if (cardIds.isEmpty()) {
            JLabel emptyLabel = new JLabel("No cards available.");
            cardsPanel.add(emptyLabel);
            selectedCardId = null;
            return;
        }

        if (selectedCardId != null && !cardIds.contains(selectedCardId)) {
            selectedCardId = null;
        }

        for (int cardId : cardIds) {
            int mod = client.getCardMod(cardId);
            String label = String.format("Card %d (%s%d)", cardId, mod >= 0 ? "+" : "", mod);
            JButton cardButton = new JButton(label);
            cardButton.putClientProperty(CARD_ID_KEY, cardId);
            cardButton.putClientProperty(CARD_BASE_LABEL_KEY, label);
            cardButton.putClientProperty(CARD_DEFAULT_BG_KEY, cardButton.getBackground());
            cardButton.setHorizontalAlignment(SwingConstants.LEFT);
            cardButton.setEnabled(canPlay);
            cardButton.addActionListener(event -> {
                // Selecting a card arms the grid click action.
                selectedCardId = cardId;
                selectionLabel.setText(String.format("Card %d selected. Choose a target cell.", cardId));
                refreshCardSelectionStyles();
                updateGridInteractivity();
            });
            cardsPanel.add(cardButton);
        }
        refreshCardSelectionStyles();
    }

    private void syncGridFromModel() {
        Grid grid = client.getLocalGridReference();

        if (grid == null || grid.getWidth() <= 0 || grid.getHeight() <= 0) {
            gridPanel.setLayout(new BorderLayout());
            gridPanel.removeAll();
            gridPanel.add(new JLabel("Grid not initialized yet."), BorderLayout.CENTER);
            gridButtons = new JButton[0][0];
            gridWidth = 0;
            gridHeight = 0;
            revalidate();
            repaint();
            return;
        }

        if (gridWidth != grid.getWidth() || gridHeight != grid.getHeight() || gridButtons.length == 0) {
            rebuildGridStructure(grid);
        }

        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                int nextValue = grid.getValue(x, y);
                JButton button = gridButtons[y][x];
                // Compare against the value previously stored on this button.
                Integer previousValue = (Integer) button.getClientProperty(CELL_VALUE_KEY);
                boolean changed = previousValue == null || previousValue.intValue() != nextValue;
                boolean alreadyChanged = Boolean.TRUE.equals(button.getClientProperty(CELL_CHANGED_KEY));
                // Store latest per-cell state on the component.
                // Keep prior highlights while this action's cells are still syncing in.
                button.putClientProperty(CELL_CHANGED_KEY, changed || alreadyChanged);
                button.putClientProperty(CELL_VALUE_KEY, nextValue);
                button.setText(String.valueOf(nextValue));
            }
        }
        refreshGridCellStyles();
        updateGridInteractivity();
        repaint();
    }

    private void rebuildGridStructure(Grid grid) {
        gridWidth = grid.getWidth();
        gridHeight = grid.getHeight();

        gridPanel.removeAll();
        gridPanel.setLayout(new GridLayout(gridHeight, gridWidth, 4, 4));
        gridButtons = new JButton[gridHeight][gridWidth];

        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                int initialValue = grid.getValue(x, y);
                JButton cellButton = new JButton(String.valueOf(initialValue));
                if (defaultGridCellBg == null) {
                    defaultGridCellBg = cellButton.getBackground();
                    defaultGridCellFg = cellButton.getForeground();
                    defaultGridCellFont = cellButton.getFont();
                }
                // Initialize per-cell state on the button.
                cellButton.putClientProperty(CELL_VALUE_KEY, initialValue);
                cellButton.putClientProperty(CELL_CHANGED_KEY, false);
                cellButton.setToolTipText(String.format("Cell (%d,%d)", x, y));
                final int targetX = x;
                final int targetY = y;
                // Grid click attempts to play the currently selected card.
                cellButton.addActionListener(event -> applySelectedCard(targetX, targetY));
                gridButtons[y][x] = cellButton;
                gridPanel.add(cellButton);
            }
        }
        refreshGridCellStyles();
        revalidate();
    }

    private void refreshGridCellStyles() {
        if (gridButtons.length == 0) {
            return;
        }

        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                JButton button = gridButtons[y][x];
                // Style from per-cell state stored on each button.
                Integer valueObject = (Integer) button.getClientProperty(CELL_VALUE_KEY);
                int value = valueObject == null ? 0 : valueObject.intValue();
                boolean isOdd = (value & 1) == 1;
                boolean isChanged = Boolean.TRUE.equals(button.getClientProperty(CELL_CHANGED_KEY));

                Color bg = isChanged
                        ? (isOdd ? GRID_CHANGED_ODD_BG : GRID_CHANGED_BG)
                        : defaultGridCellBg;
                Color fg = isOdd ? GRID_ODD_TEXT : defaultGridCellFg;
                Font font = isOdd
                        ? defaultGridCellFont.deriveFont(Font.BOLD)
                        : defaultGridCellFont;

                button.setBackground(bg);
                button.setForeground(fg);
                button.setFont(font);
            }
        }
    }

    private void clearGridChangeHighlights() {
        for (int y = 0; y < gridButtons.length; y++) {
            for (int x = 0; x < gridButtons[y].length; x++) {
                gridButtons[y][x].putClientProperty(CELL_CHANGED_KEY, false);
            }
        }
        refreshGridCellStyles();
    }

    private void updateCardInteractivity() {
        boolean canPlay = client.canLocalPlayerPlayCardNow();
        for (Component component : cardsPanel.getComponents()) {
            if (component instanceof JButton) {
                component.setEnabled(canPlay);
            }
        }
        refreshCardSelectionStyles();
    }

    private void refreshCardSelectionStyles() {
        for (Component component : cardsPanel.getComponents()) {
            if (!(component instanceof JButton)) {
                continue;
            }
            JButton cardButton = (JButton) component;
            Integer cardId = (Integer) cardButton.getClientProperty(CARD_ID_KEY);
            String baseLabel = (String) cardButton.getClientProperty(CARD_BASE_LABEL_KEY);
            Color defaultBg = (Color) cardButton.getClientProperty(CARD_DEFAULT_BG_KEY);
            boolean isSelected = selectedCardId != null && selectedCardId.equals(cardId);

            cardButton.setText(baseLabel);
            cardButton.setBorder(isSelected
                    ? BorderFactory.createLineBorder(CARD_SELECTED_BORDER, 2)
                    : BorderFactory.createLineBorder(CARD_UNSELECTED_BORDER, 2));
            cardButton.setBackground(isSelected ? CARD_SELECTED_BG : defaultBg);
            cardButton.setOpaque(isSelected);
        }
    }

    private void updateGridInteractivity() {
        boolean cellsEnabled = client.canLocalPlayerPlayCardNow() && selectedCardId != null;
        for (int y = 0; y < gridButtons.length; y++) {
            for (int x = 0; x < gridButtons[y].length; x++) {
                JButton button = gridButtons[y][x];
                // Keep buttons enabled so Look and Feel (LAF) disabled-state painting doesn't override
                // custom odd/change colors; action validity is enforced in sendCardAction().
                button.setEnabled(true);
                button.setCursor(cellsEnabled
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        }
    }

    private void applySelectedCard(int x, int y) {
        if (selectedCardId == null) {
            selectionLabel.setText("Select a card before choosing a grid cell.");
            return;
        }

        try {
            client.sendCardAction(selectedCardId, x, y);
        } catch (ValidationException e) {
            selectionLabel.setText(e.getMessage());
            return;
        }
        // Optimistically update local instruction text while server processes action.
        selectionLabel.setText(String.format("Played card %d on cell (%d,%d).", selectedCardId, x, y));
        selectedCardId = null;
        refreshCardSelectionStyles();
        updateGridInteractivity();
        refreshStatusOnly();
    }

    // ---- Local helper utilities ----

    private JPanel createCenteredPhasePanel(String message) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel(message, SwingConstants.CENTER);
        panel.add(messageLabel, BorderLayout.CENTER);
        return panel;
    }

    private void resetView() {
        // Local UI reset for disconnect/inactive states.
        selectedCardId = null;
        gridButtons = new JButton[0][0];
        lastTurnOwnerId = Constants.DEFAULT_CLIENT_ID;
        gridWidth = 0;
        gridHeight = 0;
        selectionLabel.setText("Connect to the server to receive game data.");
        cardsPanel.removeAll();
        gridPanel.removeAll();
        refreshStatusOnly();
    }
}