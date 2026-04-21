package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
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

/**
 * Main gameplay panel that shows phase-aware status, cards, grid actions, and game events.
 */
public class GameView extends JPanel implements IConnectionEvents, IPlayerEvents, IPlayerStatusEvents, IGameBoardEvents {
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

    private Map<Long, User> players = Map.of();
    private Phase currentPhase = Phase.INACTIVE;
    private long currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
    private User localPlayerFromGame;
    private Map<Integer, Card> cardCatalog = Map.of();
    private Integer selectedCardId;
    private JButton[][] gridButtons = new JButton[0][0];
    private int gridWidth;
    private int gridHeight;

    // Main game panel: status line + phase-aware center content.
    // READY and IN_PROGRESS use play content; EVALUATION uses evaluation content.
    public GameView(Client client) {
        super(new BorderLayout(6, 6));
        this.client = client;
        this.client.registerCallback(this);

        setBorder(BorderFactory.createTitledBorder("Game"));

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        // Single-line status/instruction area shown above the board.
        status.add(selectionLabel);
        readyButton.addActionListener(event -> client.sendReadySignal());
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
    public void removeNotify() {
        client.unregisterCallback(this);
        super.removeNotify();
    }

    @Override
    public void onConnected(User localUser) {
        selectionLabel.setText("Connected. Complete the ready check to join the round.");
        refreshStatusOnly();
    }

    @Override
    public void onDisconnected() {
        resetView();
    }

    @Override
    public void onPlayersUpdated(Map<Long, User> players) {
        this.players = players;
        refreshAll();
    }

    @Override
    public void onLocalPlayerStatusUpdated(User localPlayer) {
        this.localPlayerFromGame = copyUser(localPlayer);
        if (localPlayerFromGame != null && localPlayerFromGame.getClientId() != Constants.DEFAULT_CLIENT_ID) {
            upsertPlayer(localPlayerFromGame);
        }
        refreshAll();
    }

    @Override
    public void onPlayerStatusUpdated(long playerId, boolean ready, boolean turnTaken, int points) {
        User existing = players.get(playerId);
        User updated = existing == null
                ? new User(playerId, "Unknown")
                : copyUser(existing);
        updated.setReady(ready);
        updated.setTurnTaken(turnTaken);
        updated.setPoints(points);
        upsertPlayer(updated);

        if (client.isLocalPlayer(playerId) && localPlayerFromGame != null) {
            localPlayerFromGame.setReady(ready);
            localPlayerFromGame.setTurnTaken(turnTaken);
            localPlayerFromGame.setPoints(points);
        }

        refreshAll();
    }

    @Override
    public void onAllPlayerStatusesReset() {
        java.util.HashMap<Long, User> updatedPlayers = new java.util.HashMap<>();
        players.forEach((id, player) -> {
            User copy = copyUser(player);
            copy.setReady(false);
            copy.setTurnTaken(false);
            copy.setPoints(0);
            updatedPlayers.put(id, copy);
        });
        players = updatedPlayers;
        if (localPlayerFromGame != null) {
            localPlayerFromGame.setReady(false);
            localPlayerFromGame.setTurnTaken(false);
            localPlayerFromGame.setPoints(0);
        }
        refreshAll();
    }

    @Override
    public void onGamePhaseUpdated(Phase phase) {
        this.currentPhase = phase == null ? Phase.INACTIVE : phase;
        refreshAll();
    }

    @Override
    public void onCurrentTurnUpdated(long currentTurnClientId, String currentTurnDisplayName) {
        this.currentTurnClientId = currentTurnClientId;
        refreshAll();
    }

    @Override
    public void onLocalGridUpdated(Grid localGrid) {
        refreshStatusOnly();
        syncGridFromModel();
    }

    @Override
    public void onLocalHandUpdated(User localPlayer, Map<Integer, Card> cardCatalog) {
        this.localPlayerFromGame = localPlayer;
        this.cardCatalog = cardCatalog == null ? Map.of() : cardCatalog;
        refreshAll();
    }

    // ---- View refresh helpers ----

    private void refreshAll() {
        refreshStatusOnly();
        rebuildCards();
        syncGridFromModel();
    }

    private void refreshStatusOnly() {
        User localPlayer = getLocalPlayer();
        updatePhaseVisibility();
        updateReadyControls(localPlayer);

        switch (currentPhase) {
            case INACTIVE:
                selectionLabel.setText("Use Mark Ready to join the next round.");
                break;
            case READY:
                selectionLabel.setText("Waiting for ready check to complete.");
                break;
            case IN_PROGRESS:
                if (canLocalPlayerAct(localPlayer)) {
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
        switch (currentPhase) {
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

    private void updateReadyControls(User localPlayer) {
        boolean beforeGameplay = currentPhase.ordinal() <= Phase.READY.ordinal();
        boolean localReady = localPlayer != null && localPlayer.isReady();
        readyPanel.setVisible(beforeGameplay);
        readyButton.setEnabled(beforeGameplay && !localReady);
        readyButton.setText(localReady ? "Ready" : "Mark Ready");
    }

    // ---- Renderers for interactive areas ----

    private void rebuildCards() {
        User localPlayer = getLocalPlayer();
        boolean canPlay = canLocalPlayerAct(localPlayer);
        cardsPanel.removeAll();
        List<Integer> cardIds = localPlayer == null ? List.of() : new ArrayList<>(localPlayer.getCardIds());
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
            Card card = cardCatalog.get(cardId);
            int mod = card == null ? 0 : card.getMod();
            String label = String.format("Card %d (%s%d)", cardId, mod >= 0 ? "+" : "", mod);
            JButton cardButton = new JButton(label);
            cardButton.setHorizontalAlignment(SwingConstants.LEFT);
            cardButton.setEnabled(canPlay);
            cardButton.setSelected(selectedCardId != null && selectedCardId == cardId);
            if (cardButton.isSelected()) {
                cardButton.setText(label + " [selected]");
            }
            cardButton.addActionListener(event -> {
                // Selecting a card arms the grid click action.
                selectedCardId = cardId;
                selectionLabel.setText(String.format("Card %d selected. Choose a target cell.", cardId));
                updateGridInteractivity();
                refreshStatusOnly();
            });
            cardsPanel.add(cardButton);
        }
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
                gridButtons[y][x].setText(String.valueOf(grid.getValue(x, y)));
            }
        }
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
                JButton cellButton = new JButton(String.valueOf(grid.getValue(x, y)));
                cellButton.setToolTipText(String.format("Cell (%d,%d)", x, y));
                final int targetX = x;
                final int targetY = y;
                // Grid click attempts to play the currently selected card.
                cellButton.addActionListener(event -> applySelectedCard(targetX, targetY));
                gridButtons[y][x] = cellButton;
                gridPanel.add(cellButton);
            }
        }
        revalidate();
    }

    private void updateGridInteractivity() {
        User localPlayer = getLocalPlayer();
        boolean cellsEnabled = canLocalPlayerAct(localPlayer) && selectedCardId != null;
        for (int y = 0; y < gridButtons.length; y++) {
            for (int x = 0; x < gridButtons[y].length; x++) {
                gridButtons[y][x].setEnabled(cellsEnabled);
            }
        }
    }

    private void applySelectedCard(int x, int y) {
        User localPlayer = getLocalPlayer();
        if (selectedCardId == null) {
            selectionLabel.setText("Select a card before choosing a grid cell.");
            return;
        }
        if (!canLocalPlayerAct(localPlayer)) {
            selectionLabel.setText("You cannot play a card right now.");
            return;
        }

        client.sendCardAction(selectedCardId, x, y);
        // Optimistically update local instruction text while server processes action.
        selectionLabel.setText(String.format("Played card %d on cell (%d,%d).", selectedCardId, x, y));
        selectedCardId = null;
        updateGridInteractivity();
        refreshStatusOnly();
    }

    // ---- Local helper utilities ----

    private boolean canLocalPlayerAct(User localPlayer) {
        return localPlayer != null
                && currentPhase == Phase.IN_PROGRESS
                && localPlayer.isReady()
                && !localPlayer.isTurnTaken()
                && currentTurnClientId == localPlayer.getClientId();
    }

    private User getLocalPlayer() {
        User localFromPlayers = players.get(client.getLocalPlayerId());
        if (localFromPlayers != null) {
            return localFromPlayers;
        }
        return localPlayerFromGame;
    }

    private void upsertPlayer(User player) {
        java.util.HashMap<Long, User> updatedPlayers = new java.util.HashMap<>(players);
        updatedPlayers.put(player.getClientId(), copyUser(player));
        players = updatedPlayers;
    }

    private User copyUser(User source) {
        if (source == null) {
            return null;
        }
        User copy = new User(source.getClientId(), source.getClientName());
        copy.setReady(source.isReady());
        copy.setTurnTaken(source.isTurnTaken());
        copy.setPoints(source.getPoints());
        copy.setCardIds(source.getCardIds());
        return copy;
    }

    private JPanel createCenteredPhasePanel(String message) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel(message, SwingConstants.CENTER);
        panel.add(messageLabel, BorderLayout.CENTER);
        return panel;
    }

    private void resetView() {
        // Local UI reset for disconnect/inactive states.
        players = Map.of();
        currentPhase = Phase.INACTIVE;
        currentTurnClientId = Constants.DEFAULT_CLIENT_ID;
        localPlayerFromGame = null;
        cardCatalog = Map.of();
        selectedCardId = null;
        gridButtons = new JButton[0][0];
        gridWidth = 0;
        gridHeight = 0;
        selectionLabel.setText("Connect to the server to receive game data.");
        cardsPanel.removeAll();
        gridPanel.removeAll();
        refreshStatusOnly();
    }
}