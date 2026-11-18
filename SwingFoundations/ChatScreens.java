import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

public class ChatScreens {
    private final JFrame frame = new JFrame("Chat Practice");

    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField("3000", 5);
    private final JTextField nameField = new JTextField(14);

    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatField = new JTextField();
    private String username = "guest";

    /** Opens the practice window on the Swing event thread. */
    private void showWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(520, 360);
        screens.add(buildConnectionScreen(), "connection");
        screens.add(buildNameScreen(), "name");
        screens.add(buildChatScreen(), "chat");
        frame.add(screens);
        screenLayout.show(screens, "connection");
        frame.setVisible(true);
    }

    /** Builds the connection form and opens the name card on Next. */
    private JPanel buildConnectionScreen() {
        JPanel panel = new JPanel(new FlowLayout());
        panel.add(new JLabel("Host"));
        panel.add(hostField);
        panel.add(new JLabel("Port"));
        panel.add(portField);

        JButton next = new JButton("Next");
        next.addActionListener(event -> screenLayout.show(screens, "name"));
        panel.add(next);
        return panel;
    }

    /** Builds the name form and lets the user return to settings or open the chat. */
    private JPanel buildNameScreen() {
        JPanel panel = new JPanel(new FlowLayout());
        panel.add(new JLabel("Username"));
        panel.add(nameField);

        JButton back = new JButton("Back");
        back.addActionListener(event -> screenLayout.show(screens, "connection"));
        panel.add(back);

        JButton openChat = new JButton("Open Chat");
        openChat.addActionListener(event -> {
            String name = nameField.getText().trim();
            username = name.isEmpty() ? "guest" : name;
            frame.setTitle("Chat Practice - " + username);
            screenLayout.show(screens, "chat");
            chatField.requestFocusInWindow();
        });
        panel.add(openChat);
        return panel;
    }

    /** Builds the chat transcript and its shared button and Enter actions. */
    private JPanel buildChatScreen() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JPanel input = new JPanel(new BorderLayout(8, 0));
        JButton send = new JButton("Send");
        input.add(chatField, BorderLayout.CENTER);
        input.add(send, BorderLayout.EAST);
        panel.add(input, BorderLayout.SOUTH);

        send.addActionListener(event -> sendChatFromField());
        chatField.addActionListener(event -> sendChatFromField());
        return panel;
    }

    /** Adds the composed message to the local transcript and clears the field. */
    private void sendChatFromField() {
        String text = chatField.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        chatArea.append(username + ": " + text + System.lineSeparator());
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
        chatField.setText("");
    }

    /** Schedules creation of the chat window on the Swing event thread. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatScreens().showWindow());
    }
}
