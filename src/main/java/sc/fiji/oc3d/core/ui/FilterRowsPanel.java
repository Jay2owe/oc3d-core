package sc.fiji.oc3d.core.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The min/max morphology filter table, bound to a {@link DialogModel}.
 *
 * <p>Every row starts at a non-excluding default and the model only emits a
 * predicate where the user tightened one - so the table can show every feature
 * without every feature becoming an active filter. Editing a field writes
 * straight back to the model's {@link DialogModel.FeatureRange} as text; nothing
 * is parsed here, because a half-typed {@code -} or {@code 1e} must not be
 * destroyed mid-keystroke.
 *
 * <p>The size row is optional and takes the dialog's own min/max size controls,
 * so "Size (Voxels)" lines up with the feature rows below it while still being
 * owned by the dialog that knows what widget it wants there.
 */
public final class FilterRowsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int INPUT_FIELD_COLUMNS = 4;

    /** Notifies the parent dialog that filters changed, e.g. to re-run a preview. */
    public interface ChangeCallback {
        void filtersChanged();
    }

    private final DialogModel model;
    private final ChangeCallback callback;
    private final Component minSizeControl;
    private final Component maxSizeControl;
    private final JPanel rowsContainer;
    private boolean refreshing;

    public FilterRowsPanel(DialogModel model, ChangeCallback callback) {
        this(model, callback, null, null);
    }

    /**
     * @param minSizeControl the dialog's min-size widget, or null to omit the
     *                       size row. Both size controls must be supplied
     *                       together
     */
    public FilterRowsPanel(DialogModel model,
                           ChangeCallback callback,
                           Component minSizeControl,
                           Component maxSizeControl) {
        super();
        if (model == null) {
            throw new IllegalArgumentException("model must not be null (model=null).");
        }
        this.model = model;
        this.callback = callback == null ? new ChangeCallback() {
            @Override public void filtersChanged() {}
        } : callback;
        this.minSizeControl = minSizeControl;
        this.maxSizeControl = maxSizeControl;

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        this.rowsContainer = new JPanel(new GridBagLayout());
        rowsContainer.setOpaque(false);
        rowsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(rowsContainer);
        refresh();
    }

    /**
     * Rebuilds the rows from the model.
     *
     * <p>Call after {@link DialogModel#configureForImage} - the feature list
     * itself changes with the image, because an uncalibrated stack has no
     * calibrated-volume row.
     */
    public void refresh() {
        refreshing = true;
        try {
            rowsContainer.removeAll();
            addHeader();
            int rowOffset = 1;
            if (minSizeControl != null && maxSizeControl != null) {
                addSizeRow(rowOffset);
                rowOffset++;
            }
            for (int i = 0; i < model.featureRanges().size(); i++) {
                addRangeRow(i + rowOffset, model.featureRanges().get(i));
            }
        } finally {
            refreshing = false;
        }
        rowsContainer.revalidate();
        rowsContainer.repaint();
    }

    private void addHeader() {
        GridBagConstraints gbc = baseConstraints(0);
        gbc.gridx = 0;
        rowsContainer.add(header(""), gbc);
        gbc.gridx = 1;
        rowsContainer.add(header("Min"), gbc);
        gbc.gridx = 2;
        rowsContainer.add(header("Max"), gbc);
    }

    private void addSizeRow(int row) {
        GridBagConstraints gbc = baseConstraints(row);
        gbc.gridx = 0;
        rowsContainer.add(new JLabel("Size (Voxels)"), gbc);

        gbc.gridx = 1;
        rowsContainer.add(minSizeControl, gbc);

        gbc.gridx = 2;
        rowsContainer.add(maxSizeControl, gbc);
    }

    private void addRangeRow(int row, DialogModel.FeatureRange range) {
        GridBagConstraints gbc = baseConstraints(row);
        gbc.gridx = 0;
        rowsContainer.add(new JLabel(range.label), gbc);

        JTextField min = inputField(range.minText);
        min.setToolTipText(range.feature + " minimum");
        bind(min, range, true);
        gbc.gridx = 1;
        rowsContainer.add(min, gbc);

        JTextField max = inputField(range.maxText);
        max.setToolTipText(range.feature + " maximum; Infinity is allowed");
        bind(max, range, false);
        gbc.gridx = 2;
        rowsContainer.add(max, gbc);
    }

    /**
     * A fixed-width field.
     *
     * <p>Preferred, minimum and maximum are all pinned to the same value so
     * {@code GridBagLayout} cannot stretch one row's fields wider than another's
     * and leave the table ragged.
     */
    private static JTextField inputField(String text) {
        JTextField field = new JTextField(text, INPUT_FIELD_COLUMNS);
        Dimension preferred = field.getPreferredSize();
        field.setPreferredSize(preferred);
        field.setMinimumSize(preferred);
        field.setMaximumSize(preferred);
        return field;
    }

    private void bind(final JTextField field,
                      final DialogModel.FeatureRange range,
                      final boolean minField) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                update();
            }

            private void update() {
                // Suppressed during refresh(), or populating the fields would
                // fire a change per row and re-run the caller's preview.
                if (refreshing) return;
                if (minField) {
                    range.minText = field.getText();
                } else {
                    range.maxText = field.getText();
                }
                callback.filtersChanged();
            }
        });
    }

    private static JLabel header(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        return label;
    }

    private static GridBagConstraints baseConstraints(int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.insets = new Insets(1, 0, 3, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        return gbc;
    }

    /**
     * Children may overlap, so Swing must repaint the whole panel rather than
     * just the dirty child. Without this, editing one field can leave the
     * neighbouring one's border half-drawn.
     */
    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;
    }
}
