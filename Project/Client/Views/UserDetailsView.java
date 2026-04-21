package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/**
 * User-details form that collects the display name and handles connect/back actions.
 */
public class UserDetailsView extends JPanel {
    private static final int FIELD_SPACING = 10;

    // UI components for user input and error display
    private final JTextField userField = new JTextField();
    private final JLabel userError = new JLabel();

    public UserDetailsView(Runnable onPrevious, Consumer<String> onConnect) {
        super(new BorderLayout(10, 10));

        // Main content panel with vertical layout and padding
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Username input section
        content.add(new JLabel("Username: "));
        userField.setMaximumSize(new Dimension(Integer.MAX_VALUE, userField.getPreferredSize().height));
        content.add(userField);
        userError.setVisible(false);
        content.add(userError);
        content.add(Box.createVerticalStrut(FIELD_SPACING));

        // Previous and Connect buttons for navigation
        JButton previousButton = new JButton("Previous");
        previousButton.addActionListener(event -> onPrevious.run());
        JButton connectButton = new JButton("Connect");
        connectButton.addActionListener(event -> handleConnectRequested(onConnect));
        userField.addActionListener(event -> connectButton.doClick());

        JPanel buttons = new JPanel();
        buttons.add(previousButton);
        buttons.add(connectButton);

        // Push the buttons to the bottom of the content panel
        content.add(Box.createVerticalGlue());
        content.add(buttons);

        add(content, BorderLayout.CENTER);
        setBorder(new EmptyBorder(10, 10, 10, 10));
    }

    private void handleConnectRequested(Consumer<String> connectAction) {
        String enteredUsername = userField.getText().trim();
        if (enteredUsername.isEmpty()) {
            userError.setText("Username must be provided");
            userError.setVisible(true);
            return;
        }

        clearError();
        connectAction.accept(enteredUsername);
    }

    public void setError(String message) {
        userError.setText(message);
        userError.setVisible(true);
    }

    public void clearError() {
        userError.setText("");
        userError.setVisible(false);
    }
}
