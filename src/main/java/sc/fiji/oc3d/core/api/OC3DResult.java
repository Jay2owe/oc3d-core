package sc.fiji.oc3d.core.api;

import ij.ImagePlus;
import ij.measure.ResultsTable;

/**
 * The outcome of one run: the surviving objects' label image, their statistics
 * table, and how many objects each filter left standing.
 *
 * <p>Unlike {@link sc.fiji.oc3d.core.label.LabelResult}, which is what a raw
 * labelling engine returns, this is the post-measurement, post-filter result the
 * plugin presents. It carries an {@code ij.measure.ResultsTable} because that is
 * genuinely the output artefact - the CSV the user opens - not an internal type
 * leaking outward.
 *
 * <p>Immutable: arrays are copied in and out, and the table is whatever the
 * caller supplied.
 */
public final class OC3DResult {

    private final ResultsTable statistics;
    private final ImagePlus labelImage;
    private final int[] survivingPerFilter;
    private final String[] filterLabels;

    public OC3DResult(ResultsTable statistics,
                      ImagePlus labelImage,
                      int[] survivingPerFilter,
                      String[] filterLabels) {
        this.statistics = statistics == null ? new ResultsTable() : statistics;
        this.labelImage = labelImage;
        this.survivingPerFilter = survivingPerFilter == null
                ? new int[0]
                : survivingPerFilter.clone();
        this.filterLabels = filterLabels == null
                ? new String[0]
                : filterLabels.clone();
    }

    /** One row per surviving object. Never null; empty when nothing survived. */
    public ResultsTable statistics() {
        return statistics;
    }

    /**
     * Dense {@code 1..N} label image of the surviving objects, {@code 0} for
     * background.
     *
     * <p>May be {@code null} when the caller asked for statistics only - a
     * whole-volume allocation is not made just to be discarded.
     */
    public ImagePlus labelImage() {
        return labelImage;
    }

    /** Number of surviving objects, i.e. {@code statistics().size()}. */
    public int objectCount() {
        return statistics.size();
    }

    /** True if at least one object survived. */
    public boolean foundObjects() {
        return statistics.size() > 0;
    }

    /**
     * Cumulative survivor counts, one per filter in the order they were applied.
     *
     * <p>Entry {@code i} is the count after filter {@code i} <em>and every
     * earlier filter</em>, so the series is monotonically non-increasing and the
     * last entry equals {@link #objectCount()}. This is what lets a dialog say
     * which filter did the damage instead of only reporting the total.
     */
    public int[] survivingPerFilter() {
        return survivingPerFilter.clone();
    }

    /** Formatted filter strings, indexed as {@link #survivingPerFilter()}. */
    public String[] filterLabels() {
        return filterLabels.clone();
    }
}
