package Project.Client;

import Project.Client.ClientSnapshot.Player;
import Project.Common.Card;
import Project.Common.Constants;
import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One Swing view over Client's synchronized state. Buttons queue socket work
 * off the EDT; received snapshots return to the EDT before touching widgets.
 */
public final class ClientUI extends JPanel implements ClientListener {
    private record CardChoice(int id, int modifier) {
        @Override
        public String toString() {
            return "Card " + id + " (" + (modifier >= 0 ? "+" : "") + modifier + ")";
        }
    }

    private final Client client = Client.INSTANCE;
    private final ExecutorService actions = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "client-ui-actions");
        thread.setDaemon(true);
        return thread;
    });
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final JTextField nameField = new JTextField("Alice", 14);
    private final JTextField hostField = new JTextField("localhost", 16);
    private final JTextField portField = new JTextField("3000", 6);
    private final JLabel connectionStatus = new JLabel("Choose a name before connecting");
    private final JButton connectButton = new JButton("Connect");
    private final JLabel phaseLabel = new JLabel("Phase: INACTIVE");
    private final JLabel roundLabel = new JLabel("Round: 0");
    private final JLabel readyLabel = new JLabel("Ready: 0");
    private final JLabel turnLabel = new JLabel("Current turn: none");
    private final JLabel timerLabel = new JLabel("Time: --");
    private final JButton readyButton = new JButton("Mark Ready");
    private final JButton resetButton = new JButton("Reset Session");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final DefaultListModel<String> users = new DefaultListModel<>();
    private final GridView gridPanel = new GridView(this::playAt);
    private final JComboBox<CardChoice> handBox = new JComboBox<>();
    private final JTextArea chatArea = new JTextArea();
    private final JTextArea eventsArea = new JTextArea();
    private final JTextField chatField = new JTextField();
    private final JButton chatButton = new JButton("Send");
    private final Timer displayTimer;
    private boolean connected;
    private boolean ready;
    private boolean myTurn;
    private String phase = "INACTIVE";
    private long turnId = Constants.DEFAULT_CLIENT_ID;
    private long timerDeadlineNanos;
    private boolean closed;

    /** Construct components only on Swing's event dispatch thread. */
    public ClientUI() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Construct ClientUI on the EDT");
        }
        setLayout(new BorderLayout());
        buildConnectionScreen();
        buildGameScreen();
        add(screens, BorderLayout.CENTER);
        screenLayout.show(screens, "connection");
        displayTimer = new Timer(250, event -> updateTimerLabel());
        displayTimer.start();
    }

    /** Attach once after constructing the frame and register a close cleanup. */
    public void showWindow() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Show ClientUI on the EDT");
        }
        JFrame frame = new JFrame("UCID Grid and Cards");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setContentPane(this);
        frame.setSize(1100, 720);
        frame.setMinimumSize(new Dimension(850, 580));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeView();
            }
        });
        client.subscribe(this);
        frame.setVisible(true);
    }

    /** Detach this view and close only the connection it still observes. */
    void closeView() {
        if (closed) {
            return;
        }
        closed = true;
        displayTimer.stop();
        actions.execute(client.unsubscribeForClose(this));
        actions.shutdown();
    }

    /** Build the Swing controls for choosing a server.
     */
    private void buildConnectionScreen() {
        JPanel connection = new JPanel(new GridBagLayout());
        JPanel form = new JPanel(new GridLayout(4, 2, 10, 12));
        form.setBorder(BorderFactory.createTitledBorder("Connect"));
        form.add(new JLabel("Name"));
        form.add(nameField);
        form.add(new JLabel("Host"));
        form.add(hostField);
        form.add(new JLabel("Port"));
        form.add(portField);
        form.add(connectButton);
        form.add(connectionStatus);
        connection.add(form);
        screens.add(connection, "connection");
        connectButton.addActionListener(event -> connectFromFields());
    }

    /** Build the Swing board, score, hand, and command controls.
     */
    private void buildGameScreen() {
        JPanel game = new JPanel(new BorderLayout(8, 8));
        game.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel header = new JPanel(new BorderLayout());
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        status.add(phaseLabel);
        status.add(roundLabel);
        status.add(readyLabel);
        status.add(turnLabel);
        status.add(timerLabel);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        actions.add(readyButton);
        actions.add(resetButton);
        actions.add(disconnectButton);
        header.add(status, BorderLayout.NORTH);
        header.add(actions, BorderLayout.SOUTH);
        game.add(header, BorderLayout.NORTH);

        JPanel board = new JPanel(new BorderLayout(8, 8));
        board.setBorder(BorderFactory.createTitledBorder("Grid and private hand"));
        board.add(gridPanel, BorderLayout.CENTER);
        JPanel handPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        handPanel.add(new JLabel("Your card"));
        handBox.setToolTipText("Select a card, then click a grid cell");
        handPanel.add(handBox);
        board.add(handPanel, BorderLayout.SOUTH);

        JList<String> roster = new JList<>(users);
        JScrollPane rosterPane = new JScrollPane(roster);
        rosterPane.setBorder(BorderFactory.createTitledBorder("Users"));
        rosterPane.setPreferredSize(new Dimension(245, 300));

        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        eventsArea.setEditable(false);
        eventsArea.setLineWrap(true);
        eventsArea.setWrapStyleWord(true);
        JScrollPane chatPane = new JScrollPane(chatArea);
        chatPane.setBorder(BorderFactory.createTitledBorder("Chat"));
        JScrollPane eventsPane = new JScrollPane(eventsArea);
        eventsPane.setBorder(BorderFactory.createTitledBorder("Game events"));
        JSplitPane feeds = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatPane, eventsPane);
        feeds.setResizeWeight(0.55);
        feeds.setPreferredSize(new Dimension(300, 350));

        JPanel middle = new JPanel(new BorderLayout(8, 8));
        middle.add(rosterPane, BorderLayout.WEST);
        middle.add(board, BorderLayout.CENTER);
        middle.add(feeds, BorderLayout.EAST);
        game.add(middle, BorderLayout.CENTER);

        JPanel chatInput = new JPanel(new BorderLayout(8, 0));
        chatInput.add(chatField, BorderLayout.CENTER);
        chatInput.add(chatButton, BorderLayout.EAST);
        game.add(chatInput, BorderLayout.SOUTH);
        screens.add(game, "game");

        readyButton.addActionListener(event -> enqueue(() -> send(client::requestReady)));
        resetButton.addActionListener(event -> enqueue(() -> send(client::requestReset)));
        disconnectButton.addActionListener(event -> enqueue(client::disconnectClient));
        chatButton.addActionListener(event -> sendChatFromField());
        chatField.addActionListener(event -> sendChatFromField());
    }

    /** Start connection work from the displayed name and endpoint.
     */
    private void connectFromFields() {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            connectionStatus.setText("Port must be a whole number");
            return;
        }
        if (name.isEmpty() || host.isEmpty() || port < 1 || port > 65535) {
            connectionStatus.setText("Enter a name, host, and port 1-65535");
            return;
        }
        connectButton.setEnabled(false);
        connectionStatus.setText("Connecting...");
        enqueue(() -> {
            try {
                if (!client.connectToServer(name, host, port)) {
                    showConnectionError("Already connected or invalid connection details");
                }
            } catch (IOException e) {
                showConnectionError("Connection failed: " + e.getMessage());
            }
        });
    }

    /** Display a failed connection attempt to the Swing user.
     * @param message display text
     */
    private void showConnectionError(String message) {
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            connectionStatus.setText(message);
            connectButton.setEnabled(true);
        });
    }

    /** Submit the current Swing chat entry.
     */
    private void sendChatFromField() {
        String text = chatField.getText();
        if (text.isBlank()) {
            return;
        }
        chatField.setText("");
        enqueue(() -> send(() -> client.sendChat(text)));
    }

    private interface Sending { void run() throws IOException; }

    /** Queue one ordered socket action from a Swing control.
     * @param command socket action to queue
     */
    private void send(Sending command) {
        try {
            command.run();
        } catch (IOException e) {
            onMessage("Send failed: " + e.getMessage(), false);
            client.disconnectClient();
        }
    }

    /** All UI-triggered network operations use this one ordered worker. */
    void enqueue(Runnable action) {
        if (!closed) {
            actions.execute(action);
        }
    }

    /** Queue one widget update and ignore callbacks for a closed view.
     * @param update short Swing work to run on the EDT
     */
    private void updateView(Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                update.run();
            }
        });
    }

    /** Switch between connection and game controls on the EDT.
     * @param connected whether the client has an open connection
     */
    @Override
    public void onConnectionChanged(boolean connected) {
        updateView(() -> {
            if (this.connected && !connected) {
                chatArea.setText("");
                eventsArea.setText("");
                connectionStatus.setText("Disconnected; choose a name to reconnect");
            }
            this.connected = connected;
            screenLayout.show(screens, connected ? "game" : "connection");
            connectButton.setEnabled(!connected);
            updateControls();
        });
    }

    /** Append a received message to its chat or events panel.
     * @param message display text
     * @param chat true for player chat
     */
    @Override
    public void onMessage(String message, boolean chat) {
        updateView(() -> {
            JTextArea target = chat ? chatArea : eventsArea;
            target.append(message + System.lineSeparator());
            target.setCaretPosition(target.getDocument().getLength());
        });
    }

    /** Update the roster and read the local player's control flags.
     * @param clientId local player ID
     * @param players copied public player rows
     */
    @Override
    public void onPlayersChanged(long clientId, List<Player> players) {
        updateView(() -> {
            users.clear();
            ready = false;
            myTurn = false;
            String current = "none";
            long readyCount = 0;
            for (Player player : players) {
                users.addElement(player.name()
                        + (player.myTurn() ? " [current turn]" : "")
                        + (player.ready() ? " [ready]" : "")
                        + (player.acted() ? " [acted]" : "")
                        + "  points " + player.points()
                        + "  cards " + player.cardCount());
                if (player.ready()) {
                    readyCount++;
                }
                if (player.myTurn()) {
                    current = player.name();
                }
                if (player.id() == clientId) {
                    ready = player.ready();
                    myTurn = player.myTurn();
                }
            }
            readyLabel.setText("Ready: " + readyCount);
            turnLabel.setText("Current turn: " + current);
            updateControls();
        });
    }

    /** Refresh game labels when the accepted phase, round, or turn changes.
     * @param phase accepted phase
     * @param round current round
     * @param currentTurnId ID of the current player, or the default ID
     */
    @Override
    public void onGameChanged(String phase, int round, long currentTurnId) {
        updateView(() -> {
            if (!this.phase.equals(phase)) {
                eventsArea.append("Phase: " + phase + System.lineSeparator());
            }
            if (turnId != currentTurnId) {
                eventsArea.append("Current turn: " + currentTurnId + System.lineSeparator());
            }
            this.phase = phase;
            turnId = currentTurnId;
            phaseLabel.setText("Phase: " + phase);
            roundLabel.setText("Round: " + round);
            updateControls();
        });
    }

    /** Display a board update on the EDT.
     * @param values copied board rows sent by Client
     */
    @Override
    public void onBoardChanged(List<List<Integer>> values) {
        updateView(() -> renderGrid(values));
    }

    /** Show the held cards and keep the selected card if it still exists.
     * @param cards copied definitions for this player's private hand
     */
    @Override
    public void onHandChanged(List<Card> cards) {
        updateView(() -> {
            CardChoice selected = (CardChoice) handBox.getSelectedItem();
            handBox.removeAllItems();
            for (Card card : cards) {
                handBox.addItem(new CardChoice(card.getId(), card.getModifier()));
            }
            if (selected != null) {
                handBox.setSelectedItem(selected);
            }
            updateControls();
        });
    }

    /** Start or clear the local countdown displayed by the Swing timer.
     * @param remainingMillis approximate time supplied by Client
     */
    @Override
    public void onTimerChanged(int remainingMillis) {
        updateView(() -> {
            timerDeadlineNanos = remainingMillis == 0 ? 0
                    : System.nanoTime() + remainingMillis * 1_000_000L;
            updateTimerLabel();
        });
    }

    /** Enable local controls from the latest event values. The server validates requests. */
    private void updateControls() {
        readyButton.setEnabled(connected && !ready
                && ("INACTIVE".equals(phase) || "READY".equals(phase)));
        resetButton.setEnabled(connected && ready);
        handBox.setEnabled(canPlay());
        gridPanel.setEnabled(canPlay());
    }

    /** Check whether the current view allows sending a card action.
     * @return true for this player's active turn with a held card
     */
    private boolean canPlay() {
        return connected && "IN_PROGRESS".equals(phase) && myTurn && handBox.getItemCount() > 0;
    }

    /** Render the accepted board with the current play eligibility.
     * @param values immutable board rows
     */
    private void renderGrid(List<List<Integer>> values) {
        gridPanel.showGrid(values, canPlay());
    }

    /** Send a selected card and clicked board coordinate.
     * @param x target column
     * @param y target row
     */
    private void playAt(int x, int y) {
        CardChoice selected = (CardChoice) handBox.getSelectedItem();
        if (!canPlay() || selected == null) {
            return;
        }
        enqueue(() -> send(() -> client.playCard(selected.id(), x, y)));
    }

    /** Refresh only the local approximate countdown label.
     */
    private void updateTimerLabel() {
        if (timerDeadlineNanos == 0) {
            timerLabel.setText("Time: --");
            return;
        }
        long left = Math.max(0, (timerDeadlineNanos - System.nanoTime()) / 1_000_000);
        timerLabel.setText("Time: " + ((left + 999) / 1000) + "s approx.");
    }

    /** Start the optional GUI without changing the terminal entry point. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientUI().showWindow());
    }
}
