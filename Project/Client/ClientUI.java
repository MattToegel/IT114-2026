package Project.Client;

import Project.Common.Card;
import javax.swing.*;
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
            return "Card " + id + " (+" + modifier + ")";
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
    private final JPanel gridPanel = new JPanel();
    private final JComboBox<CardChoice> handBox = new JComboBox<>();
    private final JTextArea chatArea = new JTextArea();
    private final JTextArea eventsArea = new JTextArea();
    private final JTextField chatField = new JTextField();
    private final JButton chatButton = new JButton("Send");
    private final javax.swing.Timer displayTimer;
    private JButton[][] cells = new JButton[0][0];
    private List<Integer> lastHand = List.of();
    private ClientSnapshot shown;
    private long snapshotAtNanos;
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
        displayTimer = new javax.swing.Timer(250, event -> updateTimerLabel());
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
        client.attachListener(this);
        frame.setVisible(true);
    }

    /** Detach this view and close only the connection it still observes. */
    void closeView() {
        if (closed) {
            return;
        }
        closed = true;
        displayTimer.stop();
        actions.execute(client.detachListenerForClose(this));
        actions.shutdown();
    }

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

    private void showConnectionError(String message) {
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            connectionStatus.setText(message);
            connectButton.setEnabled(true);
        });
    }

    private void sendChatFromField() {
        String text = chatField.getText();
        if (text.isBlank()) {
            return;
        }
        chatField.setText("");
        enqueue(() -> send(() -> client.sendChat(text)));
    }

    private interface Sending { void run() throws IOException; }

    private void send(Sending command) {
        try {
            command.run();
        } catch (IOException e) {
            onMessage("Send failed: " + e.getMessage());
            client.disconnectClient();
        }
    }

    /** All UI-triggered network operations use this one ordered worker. */
    void enqueue(Runnable action) {
        if (!closed) {
            actions.execute(action);
        }
    }

    @Override
    public void onState(ClientSnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                render(snapshot);
            }
        });
    }

    @Override
    public void onMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            JTextArea target = message.matches("^.+#\\d+: .*") ? chatArea : eventsArea;
            target.append(message + System.lineSeparator());
            target.setCaretPosition(target.getDocument().getLength());
        });
    }

    /** Replace widgets only from ordinary client values on the EDT. */
    private void render(ClientSnapshot snapshot) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Swing update off EDT");
        }
        if (shown != null && shown.connected() && !snapshot.connected()) {
            chatArea.setText("");
            eventsArea.setText("");
        }
        if (shown == null || !shown.phase().equals(snapshot.phase())) {
            eventsArea.append("Phase: " + snapshot.phase() + System.lineSeparator());
        }
        if (shown == null || shown.currentTurnId() != snapshot.currentTurnId()) {
            eventsArea.append("Current turn: " + snapshot.currentTurnId() + System.lineSeparator());
        }
        shown = snapshot;
        snapshotAtNanos = System.nanoTime();
        screenLayout.show(screens, snapshot.connected() ? "game" : "connection");
        connectButton.setEnabled(!snapshot.connected());
        if (!snapshot.connected()) {
            connectionStatus.setText("Disconnected; choose a name to reconnect");
        }

        phaseLabel.setText("Phase: " + snapshot.phase());
        roundLabel.setText("Round: " + snapshot.round());
        readyLabel.setText("Ready: " + snapshot.readyCount());
        String current = "none";
        for (ClientSnapshot.Player player : snapshot.players()) {
            if (player.id() == snapshot.currentTurnId()) {
                current = player.name();
            }
        }
        turnLabel.setText("Current turn: " + current);
        updateTimerLabel();

        users.clear();
        boolean ownReady = false;
        for (ClientSnapshot.Player player : snapshot.players()) {
            users.addElement(player.name()
                    + (player.ready() ? " [ready]" : "")
                    + (player.acted() ? " [acted]" : "")
                    + "  points " + player.points()
                    + "  cards " + player.cardCount());
            if (player.id() == snapshot.clientId()) {
                ownReady = player.ready();
            }
        }
        readyButton.setEnabled(snapshot.connected()
                && ("INACTIVE".equals(snapshot.phase()) || "READY".equals(snapshot.phase()))
                && !ownReady);
        resetButton.setEnabled(snapshot.connected() && ownReady);
        handBox.setEnabled(snapshot.canPlay());
        if (!lastHand.equals(snapshot.hand())) {
            lastHand = snapshot.hand();
            handBox.removeAllItems();
            for (int id : snapshot.hand()) {
                Card definition = snapshot.catalog().stream()
                        .filter(card -> card.getId() == id).findFirst().orElse(null);
                handBox.addItem(new CardChoice(id,
                        definition == null ? 0 : definition.getModifier()));
            }
        }
        renderGrid(snapshot);
    }

    private void renderGrid(ClientSnapshot snapshot) {
        int height = snapshot.grid().size();
        int width = height == 0 ? 0 : snapshot.grid().get(0).size();
        if (cells.length != height || (height > 0 && cells[0].length != width)) {
            gridPanel.removeAll();
            cells = new JButton[height][width];
            gridPanel.setLayout(new GridLayout(Math.max(1, height), Math.max(1, width), 4, 4));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int cellX = x;
                    final int cellY = y;
                    JButton button = new JButton();
                    button.addActionListener(event -> playAt(cellX, cellY));
                    cells[y][x] = button;
                    gridPanel.add(button);
                }
            }
            gridPanel.revalidate();
            gridPanel.repaint();
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cells[y][x].setText(String.valueOf(snapshot.grid().get(y).get(x)));
                cells[y][x].setEnabled(snapshot.canPlay());
            }
        }
    }

    private void playAt(int x, int y) {
        CardChoice selected = (CardChoice) handBox.getSelectedItem();
        if (shown == null || !shown.canPlay() || selected == null) {
            return;
        }
        enqueue(() -> send(() -> client.playCard(selected.id(), x, y)));
    }

    private void updateTimerLabel() {
        if (shown == null || shown.remainingMillis() <= 0) {
            timerLabel.setText("Time: --");
            return;
        }
        long elapsed = (System.nanoTime() - snapshotAtNanos) / 1_000_000;
        long left = Math.max(0, shown.remainingMillis() - elapsed);
        timerLabel.setText("Time: " + ((left + 999) / 1000) + "s approx.");
    }

    /** Start the optional GUI without changing the terminal entry point. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientUI().showWindow());
    }
}
