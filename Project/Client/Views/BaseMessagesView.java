package Project.Client.Views;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import Project.Common.LoggerUtil;

/**
 * Abstract shared message-feed base that handles append, wrapping, resize, and auto-scroll behavior.
 */
public abstract class BaseMessagesView extends JPanel {
    private final JPanel messageArea = new JPanel(new GridBagLayout());
    private final GridBagConstraints glueConstraints = new GridBagConstraints();
    private final int widthMargin;
    private final int minTextWidth;
    private final int messageBottomInset;
    private final int messageRightInset;

    protected BaseMessagesView(int outerGap, int widthMargin, int minTextWidth, int messageBottomInset,
            int messageRightInset) {
        super(new BorderLayout(outerGap, outerGap));
        this.widthMargin = widthMargin;
        this.minTextWidth = minTextWidth;
        this.messageBottomInset = messageBottomInset;
        this.messageRightInset = messageRightInset;

        glueConstraints.gridx = 0;
        glueConstraints.gridy = GridBagConstraints.RELATIVE;
        glueConstraints.weighty = 1.0;
        glueConstraints.fill = GridBagConstraints.VERTICAL;
        messageArea.add(Box.createVerticalGlue(), glueConstraints);

        JScrollPane messageScroll = new JScrollPane(messageArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messageScroll.setBorder(BorderFactory.createEmptyBorder());
        add(messageScroll, BorderLayout.CENTER);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(BaseMessagesView.this::resizeEditorPanes);
            }

            @Override
            public void componentShown(ComponentEvent e) {
                SwingUtilities.invokeLater(BaseMessagesView.this::resizeEditorPanes);
            }
        });
    }

    protected void appendMessageHtml(String htmlText) {
        if (htmlText == null || htmlText.isBlank()) {
            return;
        }
        SwingUtilities.invokeLater(() -> addText(htmlText));
    }

    protected JEditorPane createMessageEditor(String htmlText) {
        JEditorPane textContainer = new JEditorPane("text/html", htmlText);
        textContainer.setEditable(false);
        textContainer.setBorder(BorderFactory.createEmptyBorder());
        textContainer.setOpaque(false);
        return textContainer;
    }

    private void addText(String htmlText) {
        LoggerUtil.INSTANCE.info("Adding message: " + htmlText);
        JEditorPane textContainer = createMessageEditor(htmlText);

        JScrollPane parentScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, messageArea);
        int availableWidth = getAvailableWidth(parentScrollPane);
        textContainer.setSize(new Dimension(availableWidth, Integer.MAX_VALUE));
        Dimension preferred = textContainer.getPreferredSize();
        textContainer.setPreferredSize(new Dimension(availableWidth, preferred.height));

        int index = messageArea.getComponentCount() - 1;
        if (index >= 0 && messageArea.getComponent(index) instanceof Box.Filler) {
            messageArea.remove(index);
        }

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(0, 0, messageBottomInset, messageRightInset);
        messageArea.add(textContainer, gbc);
        messageArea.add(Box.createVerticalGlue(), glueConstraints);

        messageArea.revalidate();
        messageArea.repaint();

        if (parentScrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar vertical = parentScrollPane.getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            });
        }
    }

    private void resizeEditorPanes() {
        JScrollPane parentScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, messageArea);
        if (parentScrollPane == null || messageArea.getParent() == null) {
            return;
        }

        int availableWidth = getAvailableWidth(parentScrollPane);
        for (Component comp : messageArea.getComponents()) {
            if (comp instanceof JEditorPane editorPane) {
                editorPane.setSize(new Dimension(availableWidth, Integer.MAX_VALUE));
                Dimension d = editorPane.getPreferredSize();
                editorPane.setPreferredSize(new Dimension(availableWidth, d.height));
                editorPane.revalidate();
            }
        }
        messageArea.revalidate();
        messageArea.repaint();
    }

    private int getAvailableWidth(JScrollPane parentScrollPane) {
        int scrollBarWidth = parentScrollPane == null
                ? 0
                : parentScrollPane.getVerticalScrollBar().getPreferredSize().width;
        int baseWidth = messageArea.getParent() == null ? getWidth() : messageArea.getParent().getWidth();
        return Math.max(baseWidth - scrollBarWidth - widthMargin, minTextWidth);
    }
}