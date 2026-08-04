package sc.fiji.oc3d.core.io;

import ij.measure.ResultsTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sc.fiji.oc3d.core.api.OC3DResult;

/**
 * Formats the one-line run summary 3D Objects Counter writes to the Log.
 *
 * <p>Wording and field order match the original 3D Objects Counter, so a user
 * scrolling a log that mixes runs from several variants reads one format, and so
 * scripts that grep the log keep working.
 *
 * <p>This class <b>formats</b> and does not log. Where the line goes - the
 * ImageJ Log, a file, a test assertion - is the plugin's decision, and a core
 * that called {@code IJ.log} could not be used from a batch run that wants the
 * line in its own report.
 */
public final class SummaryReporter {

    private SummaryReporter() {}

    /**
     * @param imageTitle    the image that was segmented
     * @param redirectTitle intensity-measurement source, or null/empty for none
     * @param result        the run's result; null is treated as zero objects
     * @param minSize       size filter lower bound, in voxels
     * @param maxSize       size filter upper bound; {@link Integer#MAX_VALUE}
     *                      prints as {@code Infinity}
     * @param threshold     intensity threshold
     */
    public static String format(String imageTitle,
                                String redirectTitle,
                                OC3DResult result,
                                int minSize,
                                int maxSize,
                                int threshold) {
        return format(imageTitle, redirectTitle,
                result == null ? 0 : result.objectCount(),
                result == null ? null : result.statistics(),
                minSize, maxSize, threshold);
    }

    /** @see #format(String, String, OC3DResult, int, int, int) */
    public static String format(String imageTitle,
                                OC3DResult result,
                                int minSize,
                                int maxSize,
                                int threshold) {
        return format(imageTitle, null, result, minSize, maxSize, threshold);
    }

    /**
     * The general form, for a caller that has a table but no
     * {@link OC3DResult} - a batch runner replaying a saved table, say.
     *
     * @param objectCount number of surviving objects
     * @param statistics  per-object table used for the morphology means; may be
     *                    null or empty, in which case the means are omitted
     *                    rather than printed as NaN
     */
    public static String format(String imageTitle,
                                String redirectTitle,
                                int objectCount,
                                ResultsTable statistics,
                                int minSize,
                                int maxSize,
                                int threshold) {
        String title = measurementSubject(imageTitle, redirectTitle);
        String line = title + ": " + objectCount + " objects detected (Size filter set to "
                + minSize + "-" + formatMaxSize(maxSize)
                + " voxels, threshold set to: " + threshold + ").";
        String morphSummary = morphologySummary(statistics);
        return morphSummary.isEmpty() ? line : line + " " + morphSummary;
    }

    /** {@code "image"}, or {@code "image redirect to other"} when redirecting. */
    public static String measurementSubject(String imageTitle, String redirectTitle) {
        String title = safeTitle(imageTitle);
        String redirect = safeTitleOrEmpty(redirectTitle);
        return redirect.isEmpty() ? title : title + " redirect to " + redirect;
    }

    private static String safeTitle(String title) {
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

    private static String safeTitleOrEmpty(String title) {
        return title == null || title.isEmpty() ? "" : title;
    }

    private static String formatMaxSize(int maxSize) {
        return maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize);
    }

    /**
     * Means of the columns worth a glance, skipping any the table does not have.
     *
     * <p>Volume and surface are found by prefix because their headings carry the
     * image's calibration unit.
     */
    private static String morphologySummary(ResultsTable stats) {
        if (stats == null || stats.size() == 0) return "";
        List<String> values = new ArrayList<String>();
        addMean(values, stats, "Nb of obj. voxels", "Size");
        addMean(values, stats, firstHeadingStartingWith(stats, "Volume ("), "Volume");
        addMean(values, stats, firstHeadingStartingWith(stats, "Surface ("), "Surface area");
        addMean(values, stats, "Morph_Sphericity", "Sphericity");
        addMean(values, stats, "Morph_Compactness", "Compactness");
        addMean(values, stats, "Morph_Elongation", "Elongation");
        addMean(values, stats, "Mean", "Mean intensity");
        addMean(values, stats, "Max", "Max intensity");
        addMean(values, stats, "Morph_Feret3D_um", "Max Feret diameter");
        return values.isEmpty() ? "" : "Morphology means: " + join(values) + ".";
    }

    private static void addMean(List<String> values,
                                ResultsTable stats,
                                String column,
                                String label) {
        if (values == null || stats == null || column == null || label == null) return;
        if (stats.getColumnIndex(column) < 0) return;
        double sum = 0.0;
        int count = 0;
        for (int row = 0; row < stats.size(); row++) {
            try {
                double value = stats.getValue(column, row);
                if (Double.isFinite(value)) {
                    sum += value;
                    count++;
                }
            } catch (RuntimeException unreadableCell) {
                // Sparse ResultsTable columns can miss cells; skip those rows.
            }
        }
        if (count > 0) {
            values.add(label + "=" + formatDouble(sum / count));
        }
    }

    private static String firstHeadingStartingWith(ResultsTable stats, String prefix) {
        if (stats == null || prefix == null) return null;
        String[] headings = stats.getHeadings();
        if (headings == null) return null;
        for (int i = 0; i < headings.length; i++) {
            String heading = headings[i];
            if (heading != null && heading.startsWith(prefix)) {
                return heading;
            }
        }
        return null;
    }

    /** Whole numbers print without a decimal point; everything else to 3 places. */
    private static String formatDouble(double value) {
        if (!Double.isFinite(value)) return "NaN";
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.3f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(values.get(i));
        }
        return out.toString();
    }
}
