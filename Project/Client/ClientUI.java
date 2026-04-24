package Project.Client;

import java.awt.CardLayout;
import java.awt.Container;
import java.awt.Dimension;

import javax.swing.JFrame;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;

import Project.Client.Interfaces.IConnectionEvents;
import Project.Client.Views.ChatGameView;
import Project.Client.Views.ChatGameView.GamePaneVisibilityMode;
import Project.Client.Views.ConnectionView;
import Project.Client.Views.UserDetailsView;
import Project.Common.User;
import Project.Exceptions.ValidationException;

public class ClientUI extends JFrame implements IConnectionEvents {
    private enum Screen {
        CONNECTION,
        USER,
        CHAT
    }

    private final CardLayout cardLayout = new CardLayout();
    private final Container container;

    private final JMenu connectionMenu = new JMenu("Connection");
    private final JMenuItem disconnectItem = new JMenuItem("Disconnect");
    private final JMenu gameMenu = new JMenu("Game");
    private final JMenuItem startReadyCheckItem = new JMenuItem("Mark Ready");
    private final JRadioButtonMenuItem autoModeItem = new JRadioButtonMenuItem("Auto (Phase-driven)", true);
    private final JRadioButtonMenuItem showModeItem = new JRadioButtonMenuItem("Force Show");
    private final JRadioButtonMenuItem hideModeItem = new JRadioButtonMenuItem("Force Hide");

    private ConnectionView connectionView;
    private UserDetailsView userDetailsView;
    private ChatGameView chatGameView;

    public ClientUI() {
        super("MT85 (change this) Client UI"); // replace with your UCID and remove the "(change this)" part
        Client.INSTANCE.registerCallback(this);
        Client.INSTANCE.startNetworkOnly();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));

        JMenuBar menuBar = new JMenuBar();
        disconnectItem.addActionListener(event -> Client.INSTANCE.disconnectFromServer());
        connectionMenu.add(disconnectItem);
        menuBar.add(connectionMenu);

        startReadyCheckItem.addActionListener(event -> {
            try {
                Client.INSTANCE.sendReadySignal();
            } catch (ValidationException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), "Validation", JOptionPane.WARNING_MESSAGE);
            }
        });

        ButtonGroup gamePaneModeGroup = new ButtonGroup();
        gamePaneModeGroup.add(autoModeItem);
        gamePaneModeGroup.add(showModeItem);
        gamePaneModeGroup.add(hideModeItem);

        autoModeItem.addActionListener(event -> {
            if (chatGameView != null) {
                chatGameView.setGamePaneVisibilityMode(GamePaneVisibilityMode.AUTO);
            }
        });
        showModeItem.addActionListener(event -> {
            if (chatGameView != null) {
                chatGameView.setGamePaneVisibilityMode(GamePaneVisibilityMode.FORCE_SHOW);
            }
        });
        hideModeItem.addActionListener(event -> {
            if (chatGameView != null) {
                chatGameView.setGamePaneVisibilityMode(GamePaneVisibilityMode.FORCE_HIDE);
            }
        });

        gameMenu.add(startReadyCheckItem);
        gameMenu.addSeparator();
        gameMenu.add(autoModeItem);
        gameMenu.add(showModeItem);
        gameMenu.add(hideModeItem);
        menuBar.add(gameMenu);
        setJMenuBar(menuBar);
        updateConnectedMenuState(false);

        container = getContentPane();
        container.setLayout(cardLayout);

        connectionView = new ConnectionView((host, port) -> {
            pendingHost = host;
            pendingPort = port;
            userDetailsView.clearError();
            showScreen(Screen.USER);
        });

        userDetailsView = new UserDetailsView(
                () -> showScreen(Screen.CONNECTION),
                name -> {
                    userDetailsView.clearError();
                    boolean connecting = Client.INSTANCE.connectToServer(pendingHost, pendingPort, name);
                    if (connecting) {
                        showScreen(Screen.CHAT);
                    } else {
                        userDetailsView.setError("Connection attempt failed. Check host/port and try again.");
                    }
                });        

        chatGameView = new ChatGameView();

        container.add(connectionView, Screen.CONNECTION.name());
        container.add(userDetailsView, Screen.USER.name());
        container.add(chatGameView, Screen.CHAT.name());

        showScreen(Screen.CONNECTION);

        setLocationRelativeTo(null);
        setVisible(true);
    }

    private String pendingHost = "localhost";
    private int pendingPort = 3000;

    private void onUiThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        SwingUtilities.invokeLater(action);
    }

    private void showScreen(Screen screen) {
        cardLayout.show(container, screen.name());
    }

    private void updateConnectedMenuState(boolean connected) {
        // Hide connection/game actions when not connected to avoid invalid operations.
        disconnectItem.setVisible(connected);
        gameMenu.setVisible(connected);
    }

    @Override
    public void onConnected(User localUser) {
        onUiThread(() -> {
            showScreen(Screen.CHAT);
            updateConnectedMenuState(true);
            if (localUser.getClientName() != null) {
                setTitle("Client UI - " + localUser.getClientName());
            }
        });
    }

    @Override
    public void onDisconnected() {
        onUiThread(() -> {
            showScreen(Screen.CONNECTION);
            updateConnectedMenuState(false);
            setTitle("Client UI");
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ClientUI::new);
    }
}
