package Clicky.Client.Views;

import java.awt.BorderLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import Clicky.Client.Client;
import Clicky.Client.Interfaces.IGameFlowEvents;
import Clicky.Common.Phase;

/**
 * Composite layout wrapper that arranges game, chat, and user list panes.
 */
public class ChatGameView extends JPanel implements IGameFlowEvents {
    private static final double CHAT_USER_SPLIT_PERCENT = 0.6;
    private static final double GAME_SPLIT_PERCENT = 0.45;

    private final GameView gameView;
    private final JSplitPane chatUserSplit;
    private final JSplitPane mainSplit;

    public enum GamePaneVisibilityMode {
        AUTO,
        FORCE_SHOW,
        FORCE_HIDE
    }

    private GamePaneVisibilityMode visibilityMode = GamePaneVisibilityMode.AUTO;

    // Outer split: game vs (chat + users). Inner split: chat vs users.
    public ChatGameView() {
        super(new BorderLayout(8, 8));
        this.gameView = new GameView(Client.INSTANCE);
        ChatView chatView = new ChatView();
        UserListView userListView = new UserListView();
        chatUserSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatView, userListView);
        chatUserSplit.setResizeWeight(CHAT_USER_SPLIT_PERCENT);
        chatUserSplit.setDividerLocation(CHAT_USER_SPLIT_PERCENT);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, gameView, chatUserSplit);
        mainSplit.setResizeWeight(GAME_SPLIT_PERCENT);
        mainSplit.setDividerLocation(GAME_SPLIT_PERCENT);

        // Keep proportions stable when the window is resized or shown again.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Re-apply ratios when container size changes.
                SwingUtilities.invokeLater(ChatGameView.this::applyPhaseLayout);
            }

            @Override
            public void componentShown(ComponentEvent e) {
                SwingUtilities.invokeLater(ChatGameView.this::applyPhaseLayout);
            }
        });

        add(mainSplit, BorderLayout.CENTER);

        applyPhaseLayout();
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

    @Override
    public void onGamePhaseUpdated(Phase phase) {
        SwingUtilities.invokeLater(this::applyPhaseLayout);
    }
    public void setGamePaneVisibilityMode(GamePaneVisibilityMode mode) {
        visibilityMode = mode == null ? GamePaneVisibilityMode.AUTO : mode;
        SwingUtilities.invokeLater(this::applyPhaseLayout);
    }

    public GamePaneVisibilityMode getGamePaneVisibilityMode() {
        return visibilityMode;
    }

    public boolean isGameVisible() {
        Phase currentPhase = Client.INSTANCE.getCurrentGamePhase();
        switch (visibilityMode) {
            case FORCE_SHOW:
                return true;
            case FORCE_HIDE:
                return false;
            case AUTO:
            default:
                return currentPhase.ordinal() >= Phase.READY.ordinal();
        }
    }

    private void applyPhaseLayout() {
        boolean showGame = isGameVisible();
        gameView.setVisible(showGame);

        chatUserSplit.setDividerLocation(CHAT_USER_SPLIT_PERCENT);
        if (showGame) {
            mainSplit.setDividerSize(8);
            mainSplit.setEnabled(true);
            mainSplit.setDividerLocation(GAME_SPLIT_PERCENT);
        } else {
            mainSplit.setDividerSize(0);
            mainSplit.setEnabled(false);
            mainSplit.setDividerLocation(0.0);
        }

        revalidate();
        repaint();
    }
}
