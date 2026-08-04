package sc.fiji.oc3d.core.ui;

import ij.ImagePlus;
import ij.measure.Calibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import sc.fiji.oc3d.core.api.MorphPredicate;
import sc.fiji.oc3d.core.macro.MacroOptions;

/**
 * The Swing-free half of a variant's dialog: what the user has selected, whether
 * it is valid, and what it means as filters or as a macro-options string.
 *
 * <p><b>Why a model and not just a dialog.</b> A {@code JDialog} cannot be unit
 * tested without a display, and the interesting logic - which filters a set of
 * text fields implies, whether {@code max} is below {@code min}, what the macro
 * recorder should write - is exactly the part worth testing. So the dialog owns
 * only the widgets and reads and writes everything through this class.
 *
 * <p><b>Extending it.</b> This holds what every variant has: size limits, edge
 * exclusion, which outputs to show, a redirect, the filter table. A variant adds
 * its engine's own settings as fields on a subclass and overrides the four hooks
 * - {@link #appendEngineMacroOptions}, {@link #appendExtraMacroFlags},
 * {@link #activeAdditionalRanges}, {@link #copyAdditionalFrom} - rather than
 * this class growing a {@code threshold} that StarDist has no use for and a
 * {@code probability} that the threshold engine has no use for.
 *
 * <h2>Filters come from two places</h2>
 *
 * <ul>
 *   <li>{@link FeatureRange} rows - a min/max pair per feature, shown as a
 *       table. A row emits a predicate <b>only where the user moved it away from
 *       its default</b>. That is what lets the table show every feature without
 *       every feature becoming an active filter.</li>
 *   <li>{@link FilterRow} entries - explicit {@code feature op value} rows, used
 *       by the macro parser and by any variant offering a free-form filter
 *       list.</li>
 * </ul>
 */
public class DialogModel {

    /** An explicit {@code feature op value} filter. */
    public static final class FilterRow {
        public String feature;
        public String operator;
        public double value;
        public boolean enabled;

        public FilterRow(String feature, String operator, double value, boolean enabled) {
            this.feature = feature == null ? "sphericity" : feature;
            this.operator = operator == null ? ">=" : operator;
            this.value = value;
            this.enabled = enabled;
        }

        public FilterRow copy() {
            return new FilterRow(feature, operator, value, enabled);
        }
    }

    /**
     * One row of the min/max filter table.
     *
     * <p>Bounds are held as <b>text</b>, not as numbers, because the user is
     * typing into a text field and a half-typed "-" or "1e" must not be
     * destroyed by an eager parse. Validation happens when the model is read.
     *
     * <p>{@code minDefault} / {@code maxDefault} are the non-excluding values;
     * a row still at both defaults emits no predicate. {@code hardMin} /
     * {@code hardMax} are what the feature can mathematically be - sphericity
     * cannot exceed 1 - and a value outside them is an error, not a filter that
     * silently matches nothing.
     */
    public static final class FeatureRange {
        public final String feature;
        public final String label;
        public final String minDefault;
        public final String maxDefault;
        public final double hardMin;
        public final double hardMax;
        public String minText;
        public String maxText;

        public FeatureRange(String feature,
                            String label,
                            String minDefault,
                            String maxDefault,
                            double hardMin,
                            double hardMax) {
            this.feature = feature;
            this.label = label;
            this.minDefault = minDefault;
            this.maxDefault = maxDefault;
            this.hardMin = hardMin;
            this.hardMax = hardMax;
            this.minText = minDefault;
            this.maxText = maxDefault;
        }

        public FeatureRange copy() {
            FeatureRange copy = new FeatureRange(feature, label, minDefault, maxDefault, hardMin, hardMax);
            copy.minText = minText;
            copy.maxText = maxText;
            return copy;
        }

        /** True if the pair parses and sits inside the hard bounds. */
        public boolean accepts(String minimumText, String maximumText) {
            try {
                double minimum = parseRangeBound(minimumText, label + " minimum");
                double maximum = parseRangeBound(maximumText, label + " maximum");
                return minimum != Double.POSITIVE_INFINITY
                        && maximum != Double.NEGATIVE_INFINITY
                        && minimum <= maximum
                        && minimum >= hardMin
                        && maximum <= hardMax;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    public int minSize = 10;
    /** {@link Integer#MAX_VALUE} represents "Infinity" in the UI and in macros. */
    public int maxSize = Integer.MAX_VALUE;
    public boolean excludeOnEdges = false;
    public boolean showLabels = true;
    public boolean showSurfaces = true;
    public boolean showCentroids = true;
    public boolean showCentersOfMass = true;
    public boolean showStats = true;
    public boolean showSummary = true;
    /** Empty string = no redirect. */
    public String redirectTitle = "";

    private final List<FeatureRange> featureRanges = defaultFeatureRanges(null);
    private final List<FilterRow> filters = new ArrayList<FilterRow>();

    /** The min/max filter table, mutable in place - this is what the panel edits. */
    public List<FeatureRange> featureRanges() {
        return featureRanges;
    }

    /** Explicit filter rows, mutable in place. */
    public List<FilterRow> filters() {
        return filters;
    }

    public void addFilter(FilterRow row) {
        if (row != null) filters.add(row);
    }

    public void removeFilter(int index) {
        if (index >= 0 && index < filters.size()) filters.remove(index);
    }

    /**
     * Rebuilds the filter table for a specific image.
     *
     * <p>Only calibrated images get a {@code volume_calibrated} row: offering
     * "Volume (pixel^3)" as a filter on an uncalibrated stack invites a user to
     * type a micrometre number into a voxel-count field.
     */
    public void configureForImage(ImagePlus image) {
        featureRanges.clear();
        featureRanges.addAll(defaultFeatureRanges(calibratedVolumeUnit(image)));
    }

    /**
     * Every filter the current state implies.
     *
     * @throws IllegalArgumentException if a range bound does not parse - call
     *         {@link #validate()} first to collect all the problems instead of
     *         stopping at the first
     */
    public List<MorphPredicate> enabledPredicates() {
        List<MorphPredicate> out = new ArrayList<MorphPredicate>();
        for (FeatureRange range : featureRanges) {
            addChangedRangePredicates(out, range);
        }
        for (FeatureRange range : activeAdditionalRanges()) {
            addChangedRangePredicates(out, range);
        }
        for (FilterRow row : filters) {
            if (row == null || !row.enabled) continue;
            out.add(new MorphPredicate(row.feature,
                    MorphPredicate.Operator.fromSymbol(row.operator), row.value));
        }
        return out;
    }

    /**
     * @return an empty list when the model is valid, otherwise every problem
     *         found, phrased for a user. Collecting all of them matters: fixing
     *         one error and being shown the next is worse than being shown three
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<String>();
        if (minSize < 0) errors.add("Min size must be >= 0 (minSize=" + minSize + ").");
        if (maxSize < minSize) {
            errors.add("Max size (" + (maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize))
                    + ") must be >= min size (" + minSize + ").");
        }
        if (redirectTitle != null && !redirectTitle.isEmpty()
                && !MacroOptions.isSafeBracketedValue(redirectTitle)) {
            errors.add("Redirect image title cannot contain [, ], quotes, backslashes, or line breaks "
                    + "(redirectTitle='" + redirectTitle + "'). "
                    + "Rename the image and try again.");
        }
        List<FeatureRange> allRanges = new ArrayList<FeatureRange>(featureRanges);
        allRanges.addAll(activeAdditionalRanges());
        for (FeatureRange range : allRanges) {
            if (range == null) continue;
            try {
                double min = parseRangeBound(range.minText, range.label + " minimum");
                double max = parseRangeBound(range.maxText, range.label + " maximum");
                if (min == Double.POSITIVE_INFINITY) {
                    errors.add(range.label + ": minimum cannot be Infinity.");
                }
                if (max == Double.NEGATIVE_INFINITY) {
                    errors.add(range.label + ": maximum cannot be -Infinity.");
                }
                if (min > max) {
                    errors.add(range.label + ": minimum must be <= maximum "
                            + "(min=" + range.minText + ", max=" + range.maxText + ").");
                }
                if (min < range.hardMin) {
                    errors.add(range.label + ": minimum must be >= " + formatBound(range.hardMin)
                            + " (min=" + range.minText + ").");
                }
                if (max > range.hardMax) {
                    errors.add(range.label + ": maximum must be <= " + formatBound(range.hardMax)
                            + " (max=" + range.maxText + ").");
                }
            } catch (IllegalArgumentException invalidRange) {
                errors.add(invalidRange.getMessage());
            }
        }
        for (int i = 0; i < filters.size(); i++) {
            FilterRow row = filters.get(i);
            if (row == null || !row.enabled) continue;
            if (row.feature == null || row.feature.trim().isEmpty()) {
                errors.add("Filter " + (i + 1) + ": feature must not be blank "
                        + "(feature='" + row.feature + "').");
            }
            if (row.operator == null || !MorphPredicate.Operator.isOperator(row.operator)) {
                errors.add("Filter " + (i + 1) + ": operator must be one of >=, <=, >, < "
                        + "(operator='" + row.operator + "').");
            }
            if (!Double.isFinite(row.value)) {
                errors.add("Filter " + (i + 1) + ": value must be a finite number "
                        + "(value=" + row.value + ").");
            }
        }
        errors.addAll(validateAdditional());
        return errors;
    }

    /**
     * A macro-options string equivalent to the current state, for the recorder.
     *
     * <p>Order is engine options, then size and edge options, then variant
     * flags, then redirect, then filters, then the {@code hide_*} output flags.
     * Fixed so a recorded macro is diffable between runs.
     */
    public String toMacroOptions() {
        StringBuilder sb = new StringBuilder();
        appendEngineMacroOptions(sb);
        append(sb, "min=" + minSize);
        append(sb, "max=" + MacroOptions.formatMaxSize(maxSize));
        if (excludeOnEdges) append(sb, "exclude_edges");
        appendExtraMacroFlags(sb);
        if (redirectTitle != null && !redirectTitle.isEmpty()) {
            append(sb, "redirect=["
                    + MacroOptions.requireSafeBracketedValue(redirectTitle, "Redirect image title")
                    + "]");
        }
        for (MorphPredicate predicate : enabledPredicates()) {
            append(sb, predicate.format());
        }
        if (!showLabels) append(sb, "hide_labels");
        if (!showSurfaces) append(sb, "hide_surfaces");
        if (!showCentroids) append(sb, "hide_centroids");
        if (!showCentersOfMass) append(sb, "hide_centers_of_mass");
        if (!showStats) append(sb, "hide_stats");
        if (!showSummary) append(sb, "hide_summary");
        return sb.toString();
    }

    /** Resets to another model's state, e.g. to revert a cancelled dialog. */
    public void copyFrom(DialogModel other) {
        if (other == null) return;
        this.minSize = other.minSize;
        this.maxSize = other.maxSize;
        this.excludeOnEdges = other.excludeOnEdges;
        this.showLabels = other.showLabels;
        this.showSurfaces = other.showSurfaces;
        this.showCentroids = other.showCentroids;
        this.showCentersOfMass = other.showCentersOfMass;
        this.showStats = other.showStats;
        this.showSummary = other.showSummary;
        this.redirectTitle = other.redirectTitle == null ? "" : other.redirectTitle;
        this.featureRanges.clear();
        for (FeatureRange range : other.featureRanges) {
            this.featureRanges.add(range.copy());
        }
        this.filters.clear();
        for (FilterRow row : other.filters) {
            this.filters.add(row.copy());
        }
        copyAdditionalFrom(other);
    }

    /** An independent copy, for taking a snapshot before the user edits. */
    public DialogModel snapshot() {
        DialogModel copy = newInstance();
        copy.copyFrom(this);
        return copy;
    }

    // ---- Extension hooks -------------------------------------------------

    /**
     * Engine settings written at the front of the macro string, e.g.
     * {@code threshold=40} or {@code channel=1 probability=0.5}. Default: none.
     *
     * <p>Use {@link #append} so separators stay right.
     */
    protected void appendEngineMacroOptions(StringBuilder options) {
        // No engine settings in the base model.
    }

    /**
     * Variant flags written after the size options, e.g.
     * {@code measure_fractal_xy}. Default: none.
     */
    protected void appendExtraMacroFlags(StringBuilder options) {
        // No extra flags in the base model.
    }

    /**
     * Ranges that filter only while some toggle is on - a variant's optional
     * measurement groups. Default: none.
     *
     * <p>Kept separate from {@link #featureRanges()} so a range belonging to a
     * disabled measurement neither filters nor fails validation.
     */
    protected List<FeatureRange> activeAdditionalRanges() {
        return Collections.emptyList();
    }

    /** Extra checks. Default: none. */
    protected List<String> validateAdditional() {
        return Collections.emptyList();
    }

    /** Copies subclass fields during {@link #copyFrom}. Default: none. */
    protected void copyAdditionalFrom(DialogModel other) {
        // No subclass fields in the base model.
    }

    /**
     * An empty instance of the concrete type, for {@link #snapshot()}.
     *
     * <p>A subclass must override this, or a snapshot of it comes back as a base
     * {@code DialogModel} and loses its engine settings.
     */
    protected DialogModel newInstance() {
        return new DialogModel();
    }

    /** Appends a token, inserting a separating space when one is needed. */
    protected static void append(StringBuilder options, String token) {
        if (options == null || token == null || token.isEmpty()) return;
        if (options.length() > 0) options.append(' ');
        options.append(token);
    }

    // ---- Shared helpers --------------------------------------------------

    /** Feature names offered in a filter dropdown. */
    public static List<String> featureOptions() {
        return MorphPredicate.BASE_FEATURES;
    }

    public static List<String> operatorOptions() {
        return Collections.unmodifiableList(Arrays.asList(">=", "<=", ">", "<"));
    }

    /**
     * Parses a filter-table bound.
     *
     * <p>{@code Infinity}, {@code +Infinity}, {@code inf} and {@code -inf} are
     * accepted because they are what the defaults display; a bare number is
     * accepted; {@code NaN} is not, since a NaN bound would silently reject
     * every object.
     *
     * @throws IllegalArgumentException naming the field and the text given
     */
    public static double parseRangeBound(String text, String fieldName) {
        String label = fieldName == null || fieldName.trim().isEmpty() ? "Range bound" : fieldName;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
        if ("infinity".equalsIgnoreCase(value) || "+infinity".equalsIgnoreCase(value)
                || "inf".equalsIgnoreCase(value) || "+inf".equalsIgnoreCase(value)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-infinity".equalsIgnoreCase(value) || "-inf".equalsIgnoreCase(value)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed)) {
                throw new IllegalArgumentException(label + " must not be NaN.");
            }
            return parsed;
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(label + " must be a number or Infinity "
                    + "(value='" + text + "').", nfe);
        }
    }

    /**
     * @return the image's spatial unit, or {@code null} when it has none - an
     *         uncalibrated image, a non-positive pixel size, or the placeholder
     *         unit {@code pixel}, which ImageJ sets when nothing is known
     */
    public static String calibratedVolumeUnit(ImagePlus image) {
        if (image == null) return null;
        Calibration cal = image.getCalibration();
        if (cal == null || !hasActualSpatialUnit(cal)) return null;
        double width = cal.pixelWidth;
        double height = cal.pixelHeight;
        double depth = cal.pixelDepth;
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(depth)
                || width <= 0.0 || height <= 0.0 || depth <= 0.0) {
            return null;
        }
        String unit = cal.getUnit() == null ? "" : cal.getUnit().trim();
        return unit.length() == 0 ? null : unit;
    }

    /** Adds the {@code >=} / {@code <=} predicates a row implies, if any. */
    protected static void addChangedRangePredicates(List<MorphPredicate> out, FeatureRange range) {
        if (out == null || range == null) return;
        double min = parseRangeBound(range.minText, range.label + " minimum");
        double max = parseRangeBound(range.maxText, range.label + " maximum");
        double defaultMin = parseRangeBound(range.minDefault, range.label + " default minimum");
        double defaultMax = parseRangeBound(range.maxDefault, range.label + " default maximum");
        if (Double.isFinite(min) && Double.compare(min, defaultMin) != 0) {
            out.add(new MorphPredicate(range.feature, MorphPredicate.Operator.GE, min));
        }
        if (Double.isFinite(max) && Double.compare(max, defaultMax) != 0) {
            out.add(new MorphPredicate(range.feature, MorphPredicate.Operator.LE, max));
        }
    }

    /** The default min/max table. Protected so a variant can start from it. */
    protected static List<FeatureRange> defaultFeatureRanges(String calibratedVolumeUnit) {
        List<FeatureRange> ranges = new ArrayList<FeatureRange>();
        ranges.add(new FeatureRange("sphericity", "Sphericity", "0", "1", 0, 1));
        ranges.add(new FeatureRange("compactness", "Compactness", "0", "1", 0, 1));
        ranges.add(new FeatureRange("elongation", "Elongation", "1", "Infinity",
                1, Double.POSITIVE_INFINITY));
        if (calibratedVolumeUnit != null) {
            ranges.add(new FeatureRange("volume_calibrated",
                    "Volume (" + calibratedVolumeUnit + "^3)",
                    "0", "Infinity", 0, Double.POSITIVE_INFINITY));
        }
        ranges.add(new FeatureRange("surface_area", "Surface area", "0", "Infinity",
                0, Double.POSITIVE_INFINITY));
        // Intensity has no hard lower bound: a 32-bit image can hold negatives.
        ranges.add(new FeatureRange("mean_intensity", "Mean intensity", "0", "Infinity",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        ranges.add(new FeatureRange("max_intensity", "Max intensity", "0", "Infinity",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        ranges.add(new FeatureRange("feret_diameter_max", "Max Feret diameter", "0", "Infinity",
                0, Double.POSITIVE_INFINITY));
        return ranges;
    }

    protected static String formatBound(double value) {
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (value == Math.rint(value)) return Long.toString(Math.round(value));
        return Double.toString(value);
    }

    private static boolean hasActualSpatialUnit(Calibration cal) {
        if (cal == null) return false;
        String unit = cal.getUnit();
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 0
                && !"pixel".equals(normalized)
                && !"pixels".equals(normalized)
                && !"px".equals(normalized);
    }
}
