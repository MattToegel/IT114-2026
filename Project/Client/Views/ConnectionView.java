package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.BiConsumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

import Project.Common.ValidationUtils;
import Project.Exceptions.ValidationException;

/**
 * Initial connection form for collecting host and port before user details.
 */
public class ConnectionView extends JPanel {
    private static final int FIELD_SPACING = 10;
    private static final int ERROR_SPACING = 8;

    private final JTextField hostField = new JTextField("localhost");
    private final JTextField portField = new JTextField("3000");
    private final JLabel errorLabel = new JLabel();

    public ConnectionView(BiConsumer<String, Integer> onNext) {
        super(new BorderLayout(10, 10));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));

        content.add(new JLabel("Host:"));
        content.add(hostField);

        content.add(Box.createRigidArea(new Dimension(0, FIELD_SPACING)));

        content.add(new JLabel("Port:"));
        content.add(portField);

        errorLabel.setVisible(false);
        content.add(Box.createRigidArea(new Dimension(0, ERROR_SPACING)));
        content.add(errorLabel);

        JButton nextButton = new JButton("Next");
        nextButton.addActionListener(event -> handleNextRequested(onNext));
        hostField.addActionListener(event -> nextButton.doClick());
        portField.addActionListener(event -> nextButton.doClick());

        content.add(Box.createRigidArea(new Dimension(0, FIELD_SPACING)));
        content.add(nextButton);

        add(content, BorderLayout.CENTER);
    }

    private void handleNextRequested(BiConsumer<String, Integer> onNext) {
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        String portText = portField.getText() == null ? "" : portField.getText().trim();
        if (host.isEmpty()) {
            setError("Host is required.");
            return;
        }
        try {
            ValidationUtils.requireNotBlank(host, "Host is required.");
            int port = Integer.parseInt(portText);
            ValidationUtils.requireTrue(port > 0 && port <= 65535, "Port must be between 1 and 65535.");
            clearError();
            onNext.accept(host, port);
        } catch (ValidationException e) {
            setError(e.getMessage());
        } catch (NumberFormatException e) {
            setError("Port must be a number.");
        }
    }

    public void setError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    public void clearError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
    }
}
