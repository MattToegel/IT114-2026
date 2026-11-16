import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Builds a small standalone Swing window with two views. */
public class CardScreens {
    private final JFrame frame = new JFrame("Swing UI Foundations");
    private final CardLayout cards = new CardLayout();
    private final JPanel screens = new JPanel(cards);
    private final JTextField nameField = new JTextField(12);
    private final JButton openCounterButton = new JButton("Open counter");
    private final JButton addOneButton = new JButton("Add one");
    private final JButton backButton = new JButton("Back");
    private final JLabel greeting = new JLabel("Hello");
    private final JLabel countLabel = new JLabel("Count: 0");
    private int count;

    /** Registers the views, actions, and top-level window layout. */
    private void showWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        screens.add(buildWelcomePanel(), "welcome");
        screens.add(buildCounterPanel(), "counter");
        frame.add(screens, BorderLayout.CENTER);
        cards.show(screens, "welcome");

        openCounterButton.addActionListener(event -> {
            String entered = nameField.getText().trim();
            greeting.setText("Hello, " + (entered.isEmpty() ? "guest" : entered));
            cards.show(screens, "counter");
        });
        addOneButton.addActionListener(event -> {
            count++;
            countLabel.setText("Count: " + count);
        });
        backButton.addActionListener(event -> cards.show(screens, "welcome"));

        frame.setSize(480, 280);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Builds the welcome panel with a name field and navigation button. */
    private JPanel buildWelcomePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new FlowLayout());
        form.add(new JLabel("Name:"));
        form.add(nameField);
        form.add(openCounterButton);
        panel.add(new JLabel("Welcome"), BorderLayout.NORTH);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    /** Builds the counter panel with its value and action buttons. */
    private JPanel buildCounterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout());
        actions.add(addOneButton);
        actions.add(backButton);
        panel.add(greeting, BorderLayout.NORTH);
        panel.add(countLabel, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    /** Starts window setup on Swing's Event Dispatch Thread. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new CardScreens().showWindow());
    }
}
