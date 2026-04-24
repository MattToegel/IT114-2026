package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

import Project.Client.Client;
import Project.Client.Interfaces.IChatEvents;
import Project.Client.Interfaces.IConnectionEvents;
import Project.Common.User;

/**
 * Chat panel that renders chat/system messages and provides a message input box.
 */
public class ChatView extends BaseMessagesView implements IChatEvents, IConnectionEvents {
    private static final int INNER_GAP = 6;
    private static final int INPUT_PADDING = 5;

    private static final int OUTER_GAP = 8;
    private static final int WIDTH_MARGIN = 10;
    private static final int MIN_TEXT_WIDTH = 200;
    private static final int MESSAGE_BOTTOM_INSET = 5;
    private static final int MESSAGE_RIGHT_INSET = 5;

    // Lightweight markdown-style formatting supported in chat messages.
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern UNDERLINE_PATTERN = Pattern.compile("__(.+?)__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");

    private final JTextField messageField = new JTextField();

    // Main chat panel: scrollable history + message input.
    public ChatView() {
        super(OUTER_GAP, WIDTH_MARGIN, MIN_TEXT_WIDTH, MESSAGE_BOTTOM_INSET, MESSAGE_RIGHT_INSET);
        JPanel controls = new JPanel(new BorderLayout(INNER_GAP, INNER_GAP));

        JPanel messageInput = new JPanel(new BorderLayout(INNER_GAP, INNER_GAP));
        messageInput.setBorder(new EmptyBorder(INPUT_PADDING, INPUT_PADDING, INPUT_PADDING, INPUT_PADDING));
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(event -> {
            String text = messageField.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            // Forward the message through the client command surface.
            Client.INSTANCE.sendChatMessage(text);
            messageField.setText("");
        });
        messageField.addActionListener(event -> sendButton.doClick());

        messageInput.add(messageField, BorderLayout.CENTER);
        messageInput.add(sendButton, BorderLayout.EAST);
        controls.add(messageInput, BorderLayout.SOUTH);

        add(controls, BorderLayout.SOUTH);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Re-register when Swing attaches this view again after a card/layout switch.
        Client.INSTANCE.registerCallback(this);
    }

    @Override
    public void removeNotify() {
        // Unregister callbacks when this panel is detached.
        Client.INSTANCE.unregisterCallback(this);
        super.removeNotify();
    }

    public void appendChatMessage(String message) {
        appendMessageHtml(toSafeRichText(message));
    }

    public void appendSystemMessage(String message) {
        appendMessageHtml("<span style='color: blue;'>[System]</span> " + escapeHtml(message));
    }

    @Override
    public void onChatMessageReceived(String message) {
        appendChatMessage(message);
    }

    @Override
    public void onSystemMessageReceived(String message) {
        appendSystemMessage(message);
    }

    @Override
    public void onConnected(User localUser) {
        onSystemMessageReceived("Connected.");
    }

    @Override
    public void onDisconnected() {
        onSystemMessageReceived("Disconnected.");
    }

    @Override
    protected JEditorPane createMessageEditor(String htmlText) {
        // Chat view uses transparent message backgrounds on top of panel color.
        JEditorPane textContainer = new JEditorPane("text/html", htmlText);
        textContainer.setEditable(false);
        textContainer.setOpaque(false);
        textContainer.setBackground(new Color(0, 0, 0, 0));
        return textContainer;
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String toSafeRichText(String text) {
        String html = escapeHtml(text);

        // Escape first, then apply only safe formatting conversions.
        html = CODE_PATTERN.matcher(html).replaceAll("<code>$1</code>");
        html = BOLD_PATTERN.matcher(html).replaceAll("<b>$1</b>");
        html = UNDERLINE_PATTERN.matcher(html).replaceAll("<u>$1</u>");
        html = ITALIC_PATTERN.matcher(html).replaceAll("<i>$1</i>");

        return html
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br/>");
    }
}
