package sc.fiji.oc3d.core.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A compact Material-style toggle switch.
 *
 * <p>Lifted from CPC and Volumetric Colocalization, which carried the same
 * widget twice with no behavioural difference between them - only naming and
 * formatting. Volumetric Colocalization's spelling is the one promoted, being
 * the tidier of the two.
 *
 * <p>A widget, not a dialog. Dialogs belong to their plugin; a control every
 * dialog in the family draws belongs here, so that a change to how a toggle
 * looks does not have to be made three times and get made twice.
 *
 * <p>Listeners are {@link Runnable} rather than {@code ChangeListener} because
 * the callers only ever ask "it changed, re-evaluate" and never inspect the
 * event. Firing happens on both user clicks and {@link #setSelected}, so a
 * dependent control cannot go stale when a dialog sets its state
 * programmatically.
 */
public class ToggleSwitch extends JPanel {

    private static final int TRACK_WIDTH = 44;
    private static final int TRACK_HEIGHT = 22;
    private static final int THUMB_DIAMETER = 18;
    private static final int THUMB_MARGIN = 2;

    private static final Color COLOR_ON = new Color(76, 175, 80);
    private static final Color COLOR_OFF = new Color(189, 189, 189);
    private static final Color COLOR_DISABLED = new Color(220, 220, 220);
    private static final Color COLOR_THUMB = Color.WHITE;
    private static final Color COLOR_THUMB_SHADOW = new Color(0, 0, 0, 40);

    private boolean selected;
    private final List<Runnable> changeListeners = new ArrayList<Runnable>();

    public ToggleSwitch(boolean initialState) {
        selected = initialState;
        setOpaque(false);
        setPreferredSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setMinimumSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setMaximumSize(new Dimension(TRACK_WIDTH, TRACK_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!isEnabled()) return;
                selected = !selected;
                repaint();
                fireChangeListeners();
            }
        });
    }

    public boolean isSelected() {
        return selected;
    }

    /** Sets the state, firing listeners only when it actually moves. */
    public void setSelected(boolean value) {
        if (selected == value) return;
        selected = value;
        repaint();
        fireChangeListeners();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChangeListeners() {
        for (Runnable listener : changeListeners) listener.run();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(enabled
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        Color track = !isEnabled()
                ? COLOR_DISABLED
                : selected ? COLOR_ON : COLOR_OFF;
        copy.setColor(track);
        copy.fill(new RoundRectangle2D.Double(
                0, 0, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT));
        int thumbX = selected
                ? TRACK_WIDTH - THUMB_DIAMETER - THUMB_MARGIN
                : THUMB_MARGIN;
        int thumbY = (TRACK_HEIGHT - THUMB_DIAMETER) / 2;
        copy.setColor(COLOR_THUMB_SHADOW);
        copy.fillOval(
                thumbX + 1, thumbY + 1, THUMB_DIAMETER, THUMB_DIAMETER);
        copy.setColor(COLOR_THUMB);
        copy.fillOval(thumbX, thumbY, THUMB_DIAMETER, THUMB_DIAMETER);
        copy.dispose();
    }
}
