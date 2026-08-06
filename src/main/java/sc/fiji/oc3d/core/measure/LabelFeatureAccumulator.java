package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single measurement implementation for the 3D Objects Counter family.
 *
 * <h2>Why there is only one</h2>
 *
 * Before extraction there were two complete implementations of this: this class
 * in 3D Objects Counter+, and {@code ObjectsCounter3DWrapper.buildNativeStatisticsTable}
 * on the mcib3d path, which the plugin routed to for 32-bit, multichannel and
 * hyperstack input. Two implementations of one table is two sets of numbers that
 * have to be kept equal by hand, and they were not: surface, sphericity and
 * compactness differed between the two paths for the same object. The mcib3d one
 * is deleted; this one is the reference.
 *
 * <h2>Streaming</h2>
 *
 * One slice at a time, accumulating running sums per label. Every column except
 * one is a running sum or extremum, so no per-object voxel list is built and
 * memory is O(objects), not O(voxels). Imports are {@code ij.*} and
 * {@code java.util.*} only - nothing here needs a library.
 *
 * <p>The exception is {@code Median}, which is a selection rather than an
 * accumulation and cannot be computed from running sums. With no intensity image
 * it costs nothing, because there is nothing to take a median of. With one:
 *
 * <ul>
 *   <li><b>8- and 16-bit</b> intensity images fold into a per-label histogram
 *       once a label has as many voxels as the histogram has bins, so a label
 *       costs at most 1 KiB or 256 KiB however large it grows. Below that
 *       break-even the retained values are the smaller of the two and are kept.
 *       The histogram is exact, not an approximation: the bin index <em>is</em>
 *       the sample value.</li>
 *   <li><b>32-bit</b> intensity images retain their values, at O(voxels), and
 *       there is no way around that - an exact median of arbitrary reals needs
 *       to see them all. Retention is released per label as soon as its median
 *       is taken.</li>
 * </ul>
 *
 * <h2>Accumulation order is part of the contract</h2>
 *
 * The intensity pass walks <b>z &rarr; y &rarr; x</b> and that order is pinned.
 * Floating-point addition is not associative, so reordering it changes the last
 * bits of {@code Mean} and {@code StdDev} and turns a clean equivalence diff into
 * a noisy one. Do not parallelise the accumulation pass without a deterministic
 * reduction.
 *
 * <h2>Two surface numbers, deliberately</h2>
 *
 * <ul>
 *   <li>The reported {@code Surface} column is the calibrated exposed-face area:
 *       every face of a foreground voxel whose neighbour is not the same label,
 *       weighted by the calibrated area of that face. Anisotropy is respected.</li>
 *   <li>Sphericity and compactness use a <em>separate</em>, uncalibrated,
 *       Lindblad-weighted surface in cubic-voxel units. Mixing the two would
 *       silently redefine sphericity for anisotropic stacks.</li>
 * </ul>
 *
 * @see #scan(ImagePlus, ImagePlus, Calibration)
 */
public final class LabelFeatureAccumulator {

    /**
     * System property capping how large a label may be before per-label storage
     * switches from a dense array to a hash map.
     *
     * <p>Relocation rewrites bytecode references but not string literals, so
     * this key is the same in every shaded copy. That is intended: it is a
     * memory-tuning knob for the JVM, not per-plugin state.
     */
    public static final String MAX_DENSE_LABEL_PROPERTY =
            "sc.fiji.oc3d.core.maxDenseLabel";
    private static final int DEFAULT_MAX_DENSE_LABEL = 8 * 1024 * 1024;
    private static final int INITIAL_DENSE_LABELS = 1024;
    private static final double INV_SQRT_2 = 1.0 / Math.sqrt(2.0);
    private static final double INV_SQRT_3 = 1.0 / Math.sqrt(3.0);
    private static final double EIGENVALUE_ZERO_TOLERANCE = 1.0e-12;
    /**
     * The 13 lattice directions of the 26-neighbourhood: 3 axes, 6 face diagonals,
     * 4 body diagonals. Kept as the first members of {@link #FERET_DIRECTIONS} so
     * that no object's Feret can ever <i>decrease</i> relative to the 13-direction
     * estimate this measurement used until 2026-08-06 - a maximum over a superset of
     * directions is never smaller - and so that objects whose longest axis lies along
     * an axis or a diagonal, which is most synthetic test material and much real
     * material, keep exactly the value they had.
     */
    private static final double[][] LATTICE_FERET_DIRECTIONS = {
            {1.0, 0.0, 0.0},
            {0.0, 1.0, 0.0},
            {0.0, 0.0, 1.0},
            {INV_SQRT_2, INV_SQRT_2, 0.0},
            {INV_SQRT_2, -INV_SQRT_2, 0.0},
            {INV_SQRT_2, 0.0, INV_SQRT_2},
            {INV_SQRT_2, 0.0, -INV_SQRT_2},
            {0.0, INV_SQRT_2, INV_SQRT_2},
            {0.0, INV_SQRT_2, -INV_SQRT_2},
            {INV_SQRT_3, INV_SQRT_3, INV_SQRT_3},
            {INV_SQRT_3, INV_SQRT_3, -INV_SQRT_3},
            {INV_SQRT_3, -INV_SQRT_3, INV_SQRT_3},
            {-INV_SQRT_3, INV_SQRT_3, INV_SQRT_3}
    };

    /** Near-uniform directions added to fill the gaps the 13 leave. */
    private static final int FERET_FILL_DIRECTIONS = 51;

    /**
     * Bounded Feret estimate: directional extrema, not exact pairwise boundary
     * distance. The value can only ever be an <b>under</b>-estimate of the true
     * maximum Feret diameter, because the true maximum is attained along some
     * direction and only {@value #FERET_DIRECTION_COUNT} are sampled.
     *
     * <p>How far under is a property of the direction set, and it is bounded. For any
     * object, the exact Feret pair vector {@code v} satisfies
     * {@code extent(d) >= |v| * cos angle(v, d)} for every direction {@code d}, so the
     * estimate is at least {@code |v| * cos(gap)} where {@code gap} is the angle from
     * {@code v} to the nearest sampled direction. The worst case is therefore
     * {@code 1 - cos(covering radius)} of the set.
     *
     * <p>The 13 lattice directions alone have a covering radius of 27.567 degrees, so
     * a worst case of <b>11.35%</b>. That was not a theoretical concern: measured
     * against mcib3d's exact pairwise Feret over 61 568 real objects (microglia and
     * amyloid), the estimate under-read by 2.5% at the median, 7.2% at p95 and 11.15%
     * at worst - essentially the whole of the available error. 77% of objects were
     * more than 1% short.
     *
     * <p>This set is those 13 plus {@value #FERET_FILL_DIRECTIONS} near-uniform fill
     * directions: covering radius 15.052 degrees, worst case <b>3.43%</b>. Pinned by
     * {@code FeretDirectionsTest}, which recomputes the covering radius rather than
     * trusting this comment, against a declared 15.10 degrees / 3.45%.
     *
     * <p>A macro filtering on {@code feret_diameter_max} still admits a slightly
     * different object set than an exact computation would. The bound is what makes
     * that difference statable.
     */
    static final double[][] FERET_DIRECTIONS = feretDirections();

    /** Size of {@link #FERET_DIRECTIONS}, for javadoc and for callers that report it. */
    public static final int FERET_DIRECTION_COUNT = 64;

    /**
     * The 13 lattice directions followed by near-uniform fill directions from the
     * Fibonacci hemisphere construction.
     *
     * <p>{@link StrictMath} throughout, not {@link Math}: these constants decide
     * measured values, so they must be bit-identical on every JVM and platform, and
     * {@code Math.sin} and {@code Math.cos} are only required to be within one ulp of
     * the true result. A one-ulp difference here would make a golden file
     * machine-dependent.
     */
    private static double[][] feretDirections() {
        java.util.List<double[]> out = new java.util.ArrayList<double[]>();
        for (int i = 0; i < LATTICE_FERET_DIRECTIONS.length; i++) {
            out.add(LATTICE_FERET_DIRECTIONS[i]);
        }
        double goldenAngle = StrictMath.PI * (3.0 - StrictMath.sqrt(5.0));
        for (int i = 0; i < FERET_FILL_DIRECTIONS; i++) {
            // z over (0,1): directions are lines, so one hemisphere covers the sphere.
            double z = (i + 0.5) / FERET_FILL_DIRECTIONS;
            double radius = StrictMath.sqrt(StrictMath.max(0.0, 1.0 - z * z));
            double phi = i * goldenAngle;
            addFeretDirection(out, radius * StrictMath.cos(phi), radius * StrictMath.sin(phi), z);
        }
        return out.toArray(new double[out.size()][]);
    }

    /** Adds {@code (x,y,z)} normalised, unless it duplicates a direction already held. */
    private static void addFeretDirection(java.util.List<double[]> directions,
                                         double x, double y, double z) {
        double norm = StrictMath.sqrt(x * x + y * y + z * z);
        if (!(norm > 0.0)) return;
        double[] candidate = {x / norm, y / norm, z / norm};
        for (int i = 0; i < directions.size(); i++) {
            double[] existing = directions.get(i);
            double dot = StrictMath.abs(existing[0] * candidate[0]
                    + existing[1] * candidate[1]
                    + existing[2] * candidate[2]);
            if (dot > 1.0 - 1.0e-9) return;
        }
        directions.add(candidate);
    }

    private LabelFeatureAccumulator() {
        // Utility class.
    }

    /**
     * Measures every label in {@code labelImage}.
     *
     * @param labelImage     dense or sparse label image; {@code 0}, negative and
     *                       non-finite values are background
     * @param intensityImage optional source for intensity statistics; must match
     *                       {@code labelImage} in width, height and slice count.
     *                       {@code null} leaves the intensity columns {@code NaN}
     * @param calibration    spatial calibration; {@code null} falls back to the
     *                       label image's own, and then to unit voxels
     * @return per-label features, and a {@link Result#toStatisticsTable} that
     *         renders them
     * @throws IllegalArgumentException naming the offending image and dimension
     */
    public static Result scan(ImagePlus labelImage,
                              ImagePlus intensityImage,
                              Calibration calibration) {
        validateImages(labelImage, intensityImage);

        ImageStack labelStack = labelImage.getStack();
        ImageStack intensityStack = intensityImage == null ? null : intensityImage.getStack();
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelStack.getSize();
        Calibration effectiveCalibration = calibration == null ? labelImage.getCalibration() : calibration;
        CalibrationScales scales = CalibrationScales.from(effectiveCalibration);
        FeatureValuesByLabel valuesByLabel =
                new FeatureValuesByLabel(medianHistogramBins(intensityImage));

        for (int z = 0; z < depth; z++) {
            ImageProcessor labelProcessor = labelStack.getProcessor(z + 1);
            ImageProcessor intensityProcessor = intensityStack == null ? null
                    : intensityStack.getProcessor(z + 1);
            validateProcessor(labelProcessor, width, height, "label");
            if (intensityProcessor != null) {
                validateProcessor(intensityProcessor, width, height, "intensity");
            }
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = offset + x;
                    int label = labelFromPixel(labelProcessor.getf(index));
                    if (label <= 0) continue;
                    FeatureValues values = valuesByLabel.getOrCreate(label);
                    values.addVoxel(x, y, z, scales);
                    if (intensityProcessor != null) {
                        float intensity = intensityProcessor.getf(index);
                        if (Float.isFinite(intensity)) {
                            values.addIntensity(intensity, x, y, z);
                        }
                    }
                }
            }
        }

        accumulateSurfaceValues(labelStack, width, height, depth, valuesByLabel, scales);
        valuesByLabel.finish(scales);
        return new Result(valuesByLabel, scales.unit);
    }

    /** Convenience overload using the label image's own calibration. */
    public static Result scan(ImagePlus labelImage, ImagePlus intensityImage) {
        return scan(labelImage, intensityImage, null);
    }

    /**
     * Histogram bins to use for the median, or {@code 0} to retain exact values.
     *
     * <p>An 8- or 16-bit processor's {@code getf} returns the raw integer sample,
     * so the value set is bounded and a histogram gives the exact same median in
     * fixed space. 32-bit and anything else can hold arbitrary reals, where the
     * only exact median is over the retained values.
     *
     * <p>A calibration function does not change this: {@code getf} reads the raw
     * pixel, and it is {@code getPixelValue} that applies the table.
     */
    private static int medianHistogramBins(ImagePlus intensityImage) {
        if (intensityImage == null) return 0;
        switch (intensityImage.getBitDepth()) {
            case 8: return 256;
            case 16: return 65536;
            default: return 0;
        }
    }

    /** An empty table carrying the full column set, for a run that found nothing. */
    public static ResultsTable emptyStatisticsTable(Calibration calibration) {
        ResultsTable table = new ResultsTable();
        String unit = unitOf(calibration);
        Result.initialiseStatisticsHeadings(
                table,
                "Volume (" + unit + "^3)",
                "Surface (" + unit + "^2)");
        return table;
    }

    private static void validateImages(ImagePlus labelImage, ImagePlus intensityImage) {
        if (labelImage == null) {
            throw new IllegalArgumentException(
                    "labelImage must not be null (labelImage=null; expected an ImagePlus).");
        }
        ImageStack labelStack = labelImage.getStack();
        if (labelStack == null || labelStack.getSize() <= 0
                || labelImage.getWidth() <= 0 || labelImage.getHeight() <= 0) {
            throw new IllegalArgumentException("labelImage must have a non-empty stack (width="
                    + labelImage.getWidth() + ", height=" + labelImage.getHeight()
                    + ", slices=" + (labelStack == null ? 0 : labelStack.getSize()) + ").");
        }
        if (intensityImage == null) return;
        ImageStack intensityStack = intensityImage.getStack();
        if (intensityStack == null || intensityStack.getSize() <= 0) {
            throw new IllegalArgumentException("intensityImage must have a non-empty stack.");
        }
        if (intensityImage.getWidth() != labelImage.getWidth()
                || intensityImage.getHeight() != labelImage.getHeight()
                || intensityStack.getSize() != labelStack.getSize()) {
            throw new IllegalArgumentException("intensityImage dimensions must match labelImage "
                    + "(intensity=" + intensityImage.getWidth() + "x" + intensityImage.getHeight()
                    + "x" + intensityStack.getSize()
                    + ", label=" + labelImage.getWidth() + "x" + labelImage.getHeight()
                    + "x" + labelStack.getSize() + ").");
        }
    }

    private static void validateProcessor(ImageProcessor processor,
                                          int width,
                                          int height,
                                          String imageName) {
        if (processor == null || processor.getPixelCount() < width * height) {
            throw new IllegalArgumentException(imageName + " stack has an invalid slice (expected at least "
                    + ((long) width * height) + " pixels, found "
                    + (processor == null ? "no processor" : Integer.toString(processor.getPixelCount())) + ").");
        }
    }

    private static void accumulateSurfaceValues(ImageStack labelStack,
                                                int width,
                                                int height,
                                                int depth,
                                                FeatureValuesByLabel valuesByLabel,
                                                CalibrationScales scales) {
        for (int z = 0; z < depth; z++) {
            ImageProcessor previous = z == 0 ? null : labelStack.getProcessor(z);
            ImageProcessor current = labelStack.getProcessor(z + 1);
            ImageProcessor next = z == depth - 1 ? null : labelStack.getProcessor(z + 2);
            validateProcessor(current, width, height, "label");
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = offset + x;
                    int label = labelFromPixel(current.getf(index));
                    if (label <= 0) continue;

                    boolean xMinus = x == 0 || labelAt(current, index - 1) != label;
                    boolean xPlus = x == width - 1 || labelAt(current, index + 1) != label;
                    boolean yMinus = y == 0 || labelAt(current, index - width) != label;
                    boolean yPlus = y == height - 1 || labelAt(current, index + width) != label;
                    boolean zMinus = z == 0 || labelAt(previous, index) != label;
                    boolean zPlus = z == depth - 1 || labelAt(next, index) != label;

                    double exposedArea = 0.0;
                    if (xMinus) exposedArea += scales.yzFaceArea;
                    if (xPlus) exposedArea += scales.yzFaceArea;
                    if (yMinus) exposedArea += scales.xzFaceArea;
                    if (yPlus) exposedArea += scales.xzFaceArea;
                    if (zMinus) exposedArea += scales.xyFaceArea;
                    if (zPlus) exposedArea += scales.xyFaceArea;

                    if (exposedArea > 0.0) {
                        FeatureValues values = valuesByLabel.get(label);
                        if (values != null) {
                            values.surfaceVoxelCount++;
                            values.surfaceArea += exposedArea;
                            values.correctedSurfacePixels += correctedSurfaceWeight(
                                    xMinus, xPlus, yMinus, yPlus, zMinus, zPlus);
                            // Feret over surface voxels only, which is exact rather
                            // than an approximation: the voxel maximising the
                            // projection onto any direction d must have an exposed
                            // face on the axis best aligned with d, or its neighbour
                            // in that axis would be a same-label voxel with a larger
                            // projection. So no extremal voxel is ever skipped, and
                            // every interior voxel skipped could not have been one.
                            // FeretSurfaceRestrictionTest checks this against a
                            // project-every-voxel reference.
                            double px = x * scales.pixelWidth;
                            double py = y * scales.pixelHeight;
                            double pz = z * scales.pixelDepth;
                            values.addFeretPoint(px, py, pz);
                            // Centroid-to-surface distances, for the composite shape
                            // indices. The centroid is already final here: the voxel
                            // pass completed before this one started.
                            values.addSurfaceDistance(px, py, pz);
                        }
                    }
                }
            }
        }
    }

    /**
     * Per-surface-voxel area weight from Lindblad (2005), "Surface area
     * estimation of digitized 3D objects using weighted local configurations".
     *
     * <p>Voxels are classified by exposed-face count (1-6), with the 3-face case
     * split by whether an opposite pair of faces is exposed. The weights are in
     * cubic-voxel (pixel) units and deliberately ignore anisotropic calibration
     * - so sphericity and compactness mean the same thing on an anisotropic
     * stack as on an isotropic one. A voxel with all 6 faces exposed (an
     * isolated single voxel) contributes nothing.
     *
     * <p>This reproduces the corrected surface of the library that used to
     * supply these columns, so values do not move for users upgrading across the
     * extraction.
     */
    private static double correctedSurfaceWeight(boolean xMinus, boolean xPlus,
                                                 boolean yMinus, boolean yPlus,
                                                 boolean zMinus, boolean zPlus) {
        int face = 0;
        if (xMinus) face++;
        if (xPlus) face++;
        if (yMinus) face++;
        if (yPlus) face++;
        if (zMinus) face++;
        if (zPlus) face++;
        boolean oppositePair = (xMinus && xPlus) || (yMinus && yPlus) || (zMinus && zPlus);
        switch (face) {
            case 1: return 0.894;
            case 2: return 1.3409;
            case 3: return oppositePair ? 2.0 : 1.5879;
            case 4: return 8.0 / 3.0;
            case 5: return 10.0 / 3.0;
            default: return 0.0; // face == 6: an isolated voxel contributes nothing
        }
    }

    private static int labelAt(ImageProcessor processor, int index) {
        return processor == null ? 0 : labelFromPixel(processor.getf(index));
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        if (value > Integer.MAX_VALUE) return 0;
        return Math.round(value);
    }

    private static int configuredMaxDenseLabel() {
        String configured = System.getProperty(MAX_DENSE_LABEL_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_MAX_DENSE_LABEL;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed < 0 ? 0 : parsed;
        } catch (NumberFormatException invalidValue) {
            return DEFAULT_MAX_DENSE_LABEL;
        }
    }

    private static String unitOf(Calibration calibration) {
        if (calibration == null) return "pixel";
        String unit = calibration.getUnit();
        if (unit == null || unit.trim().isEmpty()) return "pixel";
        return unit;
    }

    private static double positiveOrOne(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    /** Per-label features from one {@link #scan}, plus the table that renders them. */
    public static final class Result {
        private final FeatureValuesByLabel valuesByLabel;
        private final String unit;

        private Result(FeatureValuesByLabel valuesByLabel, String unit) {
            this.valuesByLabel = valuesByLabel;
            this.unit = unit == null || unit.trim().isEmpty() ? "pixel" : unit;
        }

        /** @return features for one label, or {@code null} if that label is absent */
        public FeatureValues valuesForLabel(int label) {
            return valuesByLabel.get(label);
        }

        /** Every label present, ascending. */
        public List<Integer> labelsSorted() {
            return valuesByLabel.labelsSorted();
        }

        /** Number of labels present. */
        public int objectCount() {
            return valuesByLabel.labelsSorted().size();
        }

        /** Spatial unit the volume and surface columns are named after. */
        public String unit() {
            return unit;
        }

        /** True when per-label storage fell back from a dense array to a map. */
        public boolean usesSparseStorage() {
            return valuesByLabel.usesSparseStorage();
        }

        /** @return a fresh table, one row per label, ascending */
        public ResultsTable toStatisticsTable() {
            return toStatisticsTable(null);
        }

        /**
         * Writes the feature columns into a table.
         *
         * @param templateOrNull when null or empty, a fresh table is built with
         *        one row per label in ascending order. When it already has rows,
         *        each row's {@code Label} column decides which object's values
         *        are written into it, so a caller that has already filtered and
         *        ordered rows keeps its ordering. The template is copied, not
         *        mutated.
         */
        public ResultsTable toStatisticsTable(ResultsTable templateOrNull) {
            ResultsTable table = templateOrNull == null ? new ResultsTable() : copyOf(templateOrNull);
            String volumeColumn = "Volume (" + unit + "^3)";
            String surfaceColumn = "Surface (" + unit + "^2)";

            if (table.size() == 0) {
                initialiseStatisticsHeadings(table, volumeColumn, surfaceColumn);
                List<Integer> labels = labelsSorted();
                for (int i = 0; i < labels.size(); i++) {
                    table.incrementCounter();
                    FeatureValues values = valuesForLabel(labels.get(i).intValue());
                    if (values != null) {
                        writeStatisticsRow(table, i, values, volumeColumn, surfaceColumn);
                    }
                }
                return table;
            }

            for (int row = 0; row < table.size(); row++) {
                FeatureValues values = valuesForLabel(labelForRow(table, row));
                if (values != null) {
                    writeStatisticsRow(table, row, values, volumeColumn, surfaceColumn);
                }
            }
            return table;
        }

        /**
         * The canonical column order for every input shape.
         *
         * <p>It is deliberately the order the <b>classic</b> {@code Counter3D} path
         * emits — {@code Median} between {@code StdDev} and {@code Min}, and the
         * {@code Morph_*} block after {@code Label} rather than before {@code BX}.
         * That path is what the overwhelming majority of users see, and the
         * migration's first constraint is that it must not move. Once one engine
         * serves every input shape there can only be one column order, so it is
         * this one; the shapes that previously took the mcib3d path see the
         * {@code Morph_*} block move and gain a {@code Median} column, which is
         * recorded as a schema change rather than absorbed silently.
         */
        static void initialiseStatisticsHeadings(ResultsTable table,
                                                 String volumeColumn,
                                                 String surfaceColumn) {
            table.setHeading(0, volumeColumn);
            table.setHeading(1, surfaceColumn);
            table.setHeading(2, "Nb of obj. voxels");
            table.setHeading(3, "Nb of surf. voxels");
            table.setHeading(4, "IntDen");
            table.setHeading(5, "Mean");
            table.setHeading(6, "StdDev");
            table.setHeading(7, "Median");
            table.setHeading(8, "Min");
            table.setHeading(9, "Max");
            table.setHeading(10, "X");
            table.setHeading(11, "Y");
            table.setHeading(12, "Z");
            table.setHeading(13, "XM");
            table.setHeading(14, "YM");
            table.setHeading(15, "ZM");
            table.setHeading(16, "BX");
            table.setHeading(17, "BY");
            table.setHeading(18, "BZ");
            table.setHeading(19, "B-width");
            table.setHeading(20, "B-height");
            table.setHeading(21, "B-depth");
            table.setHeading(22, "Label");
            table.setHeading(23, "Morph_Sphericity");
            table.setHeading(24, "Morph_Compactness");
            table.setHeading(25, "Morph_Elongation");
            table.setHeading(26, "Morph_Feret3D_um");
        }

        private static void writeStatisticsRow(ResultsTable table,
                                               int row,
                                               FeatureValues values,
                                               String volumeColumn,
                                               String surfaceColumn) {
            table.setValue(volumeColumn, row, values.calibratedVolume);
            table.setValue(surfaceColumn, row, values.surfaceArea);
            table.setValue("Nb of obj. voxels", row, values.voxelCount);
            table.setValue("Nb of surf. voxels", row, values.surfaceVoxelCount);
            setFiniteOrNaN(table, "IntDen", row, values.intensitySum());
            setFiniteOrNaN(table, "Mean", row, values.intensityMean());
            setFiniteOrNaN(table, "StdDev", row, values.intensityStdDev());
            setFiniteOrNaN(table, "Median", row, values.median());
            setFiniteOrNaN(table, "Min", row, values.intensityMin());
            setFiniteOrNaN(table, "Max", row, values.intensityMax());
            table.setValue("X", row, values.centroidX());
            table.setValue("Y", row, values.centroidY());
            table.setValue("Z", row, values.centroidZ());
            table.setValue("XM", row, values.centerOfMassX());
            table.setValue("YM", row, values.centerOfMassY());
            table.setValue("ZM", row, values.centerOfMassZ());
            setFiniteOrNaN(table, "Morph_Sphericity", row, values.sphericity());
            setFiniteOrNaN(table, "Morph_Compactness", row, values.compactness());
            setFiniteOrNaN(table, "Morph_Elongation", row, values.elongation());
            setFiniteOrNaN(table, "Morph_Feret3D_um", row, values.feretDiameterMax());
            table.setValue("BX", row, values.minX);
            table.setValue("BY", row, values.minY);
            table.setValue("BZ", row, values.minZ);
            table.setValue("B-width", row, values.boundingWidth());
            table.setValue("B-height", row, values.boundingHeight());
            table.setValue("B-depth", row, values.boundingDepth());
            table.setValue("Label", row, values.label);
        }

        private static void setFiniteOrNaN(ResultsTable table,
                                           String column,
                                           int row,
                                           double value) {
            table.setValue(column, row, Double.isFinite(value) ? value : Double.NaN);
        }

        private static ResultsTable copyOf(ResultsTable source) {
            ResultsTable copy = new ResultsTable();
            if (source == null || source.size() == 0) return copy;
            String[] headings = source.getHeadings();
            for (int row = 0; row < source.size(); row++) {
                copy.incrementCounter();
                if (headings == null) continue;
                for (int h = 0; h < headings.length; h++) {
                    String heading = headings[h];
                    if (heading == null || heading.trim().isEmpty()) continue;
                    try {
                        copy.setValue(heading, row, source.getValue(heading, row));
                    } catch (RuntimeException unreadableCell) {
                        // ResultsTable columns can be sparse; leave unreadable cells empty.
                    }
                }
            }
            return copy;
        }

        private static int labelForRow(ResultsTable table, int row) {
            if (table == null || table.getColumnIndex("Label") < 0) return row + 1;
            try {
                double label = table.getValue("Label", row);
                return Double.isFinite(label) && label > 0.0 ? (int) Math.round(label) : row + 1;
            } catch (RuntimeException unreadableCell) {
                return row + 1;
            }
        }
    }

    /** Running totals for one label. Populated during {@link #scan}. */
    public static final class FeatureValues {
        /** The label these values belong to. */
        public final int label;
        long voxelCount;
        long surfaceVoxelCount;
        double calibratedVolume;
        double surfaceArea;
        /**
         * Lindblad-weighted surface in cubic-voxel (pixel) units. Feeds
         * sphericity and compactness only - the reported {@code Surface} column
         * is {@link #surfaceArea}, which is calibrated.
         */
        double correctedSurfacePixels;
        double intensitySum;
        double intensitySumSquares;
        double intensityMin = Double.POSITIVE_INFINITY;
        double intensityMax = Double.NEGATIVE_INFINITY;
        double xSum;
        double ySum;
        double zSum;
        double intensityWeightedX;
        double intensityWeightedY;
        double intensityWeightedZ;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        double xxSum;
        double yySum;
        double zzSum;
        double xySum;
        double xzSum;
        double yzSum;
        private double shapeXSum;
        private double shapeYSum;
        private double shapeZSum;
        private double shapeXXSum;
        private double shapeYYSum;
        private double shapeZZSum;
        private double shapeXYSum;
        private double shapeXZSum;
        private double shapeYZSum;
        private double elongation = Double.NaN;
        private double flatness = Double.NaN;
        private double spareness = Double.NaN;
        private double surfaceDistanceMean = Double.NaN;
        private double surfaceDistanceStdDev = Double.NaN;
        /** Running totals over surface voxels, for the two distance statistics. */
        private double surfaceDistanceSum;
        private double surfaceDistanceSumSquares;
        private long surfaceDistanceCount;
        private double feretDiameterMax = Double.NaN;
        private double[] feretMin;
        private double[] feretMax;
        private long intensityCount;
        /**
         * The object's intensity values, retained only while scanning so a median
         * can be selected. Every other statistic here is a running total; a median
         * is the one that cannot be accumulated in constant space.
         *
         * <p>Cost, measured before this was written (see the migration's
         * MEDIAN_COST.md): about 4 bytes per foreground voxel plus one array header
         * per object, so ~21 MB on a 1024x1024x50 volume at 10% foreground. The
         * array is released in {@link #finish} — unlike {@code Object3D.obj_voxels},
         * which holds every voxel for the lifetime of the result.
         */
        private float[] intensityValues;
        private int intensityValueCount;
        private double median = Double.NaN;
        /** Bins to switch to for the median, or 0 to keep the exact values. */
        private int histogramBins;
        private int[] histogram;

        private FeatureValues(int label, int histogramBins) {
            this.label = label;
            this.histogramBins = histogramBins;
        }

        private void addVoxel(double x, double y, double z, CalibrationScales scales) {
            voxelCount++;
            xSum += x;
            ySum += y;
            zSum += z;
            xxSum += x * x;
            yySum += y * y;
            zzSum += z * z;
            xySum += x * y;
            xzSum += x * z;
            yzSum += y * z;
            double px = x * scales.pixelWidth;
            double py = y * scales.pixelHeight;
            double pz = z * scales.pixelDepth;
            shapeXSum += px;
            shapeYSum += py;
            shapeZSum += pz;
            shapeXXSum += px * px;
            shapeYYSum += py * py;
            shapeZZSum += pz * pz;
            shapeXYSum += px * py;
            shapeXZSum += px * pz;
            shapeYZSum += py * pz;
            // Feret is accumulated in the surface pass instead, over surface voxels
            // only. See accumulateSurfaceValues: the restriction is lossless, and an
            // interior voxel projected here is work that cannot change the answer.
            int ix = (int) x;
            int iy = (int) y;
            int iz = (int) z;
            if (ix < minX) minX = ix;
            if (iy < minY) minY = iy;
            if (iz < minZ) minZ = iz;
            if (ix > maxX) maxX = ix;
            if (iy > maxY) maxY = iy;
            if (iz > maxZ) maxZ = iz;
        }

        private void addIntensity(double intensity, double x, double y, double z) {
            intensityCount++;
            intensitySum += intensity;
            intensitySumSquares += intensity * intensity;
            if (intensity < intensityMin) intensityMin = intensity;
            if (intensity > intensityMax) intensityMax = intensity;
            intensityWeightedX += intensity * x;
            intensityWeightedY += intensity * y;
            intensityWeightedZ += intensity * z;
            retainForMedian((float) intensity);
        }

        /**
         * Accumulates one value towards the median.
         *
         * <p>An exact median of arbitrary values needs every value, which is why
         * this started out simply retaining them. On an integer-typed intensity
         * image it does not: the values are bounded small integers, so a
         * histogram answers the same question in fixed space.
         *
         * <p>The switch happens at the break-even point — once a label has as
         * many voxels as the histogram has bins, the histogram is the smaller of
         * the two and stops growing. Below that the array is smaller, so small
         * labels keep it. Neither case is ever worse than retention was, and a
         * label covering a whole 16-bit plane costs 256 KiB instead of megabytes.
         */
        private void retainForMedian(float intensity) {
            if (histogram != null) {
                histogram[(int) intensity]++;
                intensityValueCount++;
                return;
            }
            if (intensityValues == null) {
                intensityValues = new float[16];
            } else if (intensityValueCount == intensityValues.length) {
                int grown = intensityValues.length + (intensityValues.length >> 1) + 1;
                float[] larger = new float[grown];
                System.arraycopy(intensityValues, 0, larger, 0, intensityValueCount);
                intensityValues = larger;
            }
            intensityValues[intensityValueCount++] = intensity;
            if (histogramBins > 0 && intensityValueCount >= histogramBins) {
                switchToHistogram();
            }
        }

        /** Folds the retained values into a histogram and releases the array. */
        private void switchToHistogram() {
            int[] bins = new int[histogramBins];
            for (int i = 0; i < intensityValueCount; i++) {
                float value = intensityValues[i];
                int bin = (int) value;
                if (bin < 0 || bin >= histogramBins || value != bin) {
                    // Not the integer data the bit depth promised. Keep the exact
                    // values rather than quietly rounding someone's intensities.
                    histogramBins = 0;
                    return;
                }
                bins[bin]++;
            }
            histogram = bins;
            intensityValues = null;
        }

        private void finish(CalibrationScales scales) {
            calibratedVolume = voxelCount * scales.voxelVolume;
            double[] shapeEigenvalues = shapeEigenvalues();
            elongation = computeElongation(shapeEigenvalues);
            flatness = computeFlatness(shapeEigenvalues);
            spareness = computeSpareness(shapeEigenvalues);
            if (surfaceDistanceCount > 0) {
                double inverse = 1.0 / (double) surfaceDistanceCount;
                surfaceDistanceMean = surfaceDistanceSum * inverse;
                double variance = surfaceDistanceSumSquares * inverse
                        - surfaceDistanceMean * surfaceDistanceMean;
                if (variance < 0.0 && variance > -1.0e-9) variance = 0.0;
                surfaceDistanceStdDev = variance < 0.0 ? Double.NaN : Math.sqrt(variance);
            }
            feretDiameterMax = computeFeretDiameterMax();
            median = computeMedian();
            // Both stores exist only for the selection above. Releasing the
            // histogram matters as much as releasing the array: a 16-bit histogram
            // is 65 536 ints, or 256 KiB, and only large labels switch to one - so
            // the labels that hold histograms are exactly the ones where keeping
            // them past finish() would cost the most.
            intensityValues = null;
            intensityValueCount = 0;
            histogram = null;
        }

        /**
         * The median intensity, computed exactly as {@code Utilities.Object3D.median}
         * does — decoded from the shipped bytecode of
         * {@code sc.fiji:3D_Objects_Counter:2.0.1} rather than assumed:
         *
         * <pre>
         * Arrays.sort(values);
         * even n -> (values[n/2 - 1] + values[n/2]) / 2f
         * odd  n -> values[(n + 1) / 2 - 1]
         * </pre>
         *
         * <p>Both the values and the division are {@code float}, matching the
         * reference's own type. That is deliberate: the values come from
         * {@code ImageProcessor.getf}, which is what {@code Counter3D} stores too,
         * so selecting and averaging in {@code float} reproduces the shipped
         * value bit-for-bit instead of merely rounding to it.
         */
        private double computeMedian() {
            if (intensityValueCount <= 0) return Double.NaN;
            int n = intensityValueCount;
            if (histogram != null) {
                // Same selection, same float arithmetic: the bin index is the
                // value, and both are exact in float below 2^24.
                if ((n & 1) == 0) {
                    float lower = binAtRank(n / 2 - 1);
                    float upper = binAtRank(n / 2);
                    return (lower + upper) / 2f;
                }
                return binAtRank((n + 1) / 2 - 1);
            }
            float[] sorted = new float[n];
            System.arraycopy(intensityValues, 0, sorted, 0, n);
            java.util.Arrays.sort(sorted);
            if ((n & 1) == 0) {
                int lower = n / 2 - 1;
                return (sorted[lower] + sorted[lower + 1]) / 2f;
            }
            return sorted[(n + 1) / 2 - 1];
        }

        /** The value at a zero-based rank in the histogram's implied sorted order. */
        private float binAtRank(int rank) {
            int seen = 0;
            for (int bin = 0; bin < histogram.length; bin++) {
                seen += histogram[bin];
                if (seen > rank) return bin;
            }
            return histogram.length - 1;
        }

        /** Voxels carrying this label. */
        public long voxelCount() {
            return voxelCount;
        }

        /** Voxels with at least one exposed face. */
        public long surfaceVoxelCount() {
            return surfaceVoxelCount;
        }

        /** {@link #voxelCount()} times the calibrated voxel volume. */
        public double calibratedVolume() {
            return calibratedVolume;
        }

        /** Calibrated exposed-face area - the reported {@code Surface} column. */
        public double surfaceArea() {
            return surfaceArea;
        }

        /** Lindblad-weighted surface in cubic-voxel units; feeds shape only. */
        public double correctedSurfacePixels() {
            return correctedSurfacePixels;
        }

        public boolean hasIntensityValues() {
            return intensityCount > 0;
        }

        public double intensitySum() {
            return hasIntensityValues() ? intensitySum : Double.NaN;
        }

        public double intensityMean() {
            return hasIntensityValues() ? intensitySum / (double) intensityCount : Double.NaN;
        }

        /** @see #intensityMean() */
        public double meanIntensity() {
            return intensityMean();
        }

        public double intensityMin() {
            return hasIntensityValues() ? intensityMin : Double.NaN;
        }

        public double intensityMax() {
            return hasIntensityValues() ? intensityMax : Double.NaN;
        }

        /** @see #intensityMax() */
        public double maxIntensity() {
            return intensityMax();
        }

        public double intensityStdDev() {
            if (!hasIntensityValues()) return Double.NaN;
            double mean = intensityMean();
            double variance = (intensitySumSquares / (double) intensityCount) - (mean * mean);
            if (variance < 0.0 && variance > -1.0e-9) variance = 0.0;
            return variance < 0.0 ? Double.NaN : Math.sqrt(variance);
        }

        /**
         * The median intensity, or {@code NaN} when no intensity image was given.
         *
         * @see #computeMedian()
         */
        public double median() {
            return hasIntensityValues() ? median : Double.NaN;
        }

        /**
         * Compactness, {@code 36*pi*V^2 / S^3}, from the voxel count and the
         * Lindblad-weighted corrected surface.
         *
         * <p>Both are in cubic-voxel (pixel) units and deliberately ignore
         * anisotropic calibration, so the number means the same thing across
         * every variant in the family and across every stack geometry.
         */
        public double compactness() {
            if (voxelCount <= 0 || !(correctedSurfacePixels > 0.0)) return Double.NaN;
            double v = (double) voxelCount;
            double s = correctedSurfacePixels;
            return (36.0 * Math.PI * v * v) / (s * s * s);
        }

        /** Sphericity, the cube root of {@link #compactness()}; 1 for a perfect sphere. */
        public double sphericity() {
            double c = compactness();
            if (Double.isNaN(c) || c < 0.0) return Double.NaN;
            return Math.cbrt(c);
        }

        /**
         * Square root of the ratio of largest to smallest moment-tensor
         * eigenvalue; 1 for an isotropic blob. {@code NaN} for a single voxel or
         * a shape flat enough that the smallest eigenvalue is zero.
         */
        public double elongation() {
            return elongation;
        }

        /** Middle principal axis over the shortest; see {@code computeFlatness}. */
        public double flatness() {
            return flatness;
        }

        /** Object volume over the volume of the ellipsoid with the same moments. */
        public double spareness() {
            return spareness;
        }

        /** Mean distance from the centroid to a surface voxel, in calibrated units. */
        public double surfaceDistanceMean() {
            return surfaceDistanceMean;
        }

        /** Standard deviation of those distances, in calibrated units. */
        public double surfaceDistanceStdDev() {
            return surfaceDistanceStdDev;
        }

        /** @see #FERET_DIRECTIONS - a bounded estimate, never an over-estimate */
        public double feretDiameterMax() {
            return feretDiameterMax;
        }

        public double centroidX() {
            return voxelCount <= 0 ? Double.NaN : xSum / (double) voxelCount;
        }

        public double centroidY() {
            return voxelCount <= 0 ? Double.NaN : ySum / (double) voxelCount;
        }

        public double centroidZ() {
            return voxelCount <= 0 ? Double.NaN : zSum / (double) voxelCount;
        }

        /** Intensity-weighted centre; falls back to the geometric centroid. */
        public double centerOfMassX() {
            return hasWeightedCenter() ? intensityWeightedX / intensitySum : centroidX();
        }

        /** @see #centerOfMassX() */
        public double centerOfMassY() {
            return hasWeightedCenter() ? intensityWeightedY / intensitySum : centroidY();
        }

        /** @see #centerOfMassX() */
        public double centerOfMassZ() {
            return hasWeightedCenter() ? intensityWeightedZ / intensitySum : centroidZ();
        }

        public int boundingX() {
            return voxelCount <= 0 ? 0 : minX;
        }

        public int boundingY() {
            return voxelCount <= 0 ? 0 : minY;
        }

        public int boundingZ() {
            return voxelCount <= 0 ? 0 : minZ;
        }

        public int boundingWidth() {
            return voxelCount <= 0 ? 0 : maxX - minX + 1;
        }

        public int boundingHeight() {
            return voxelCount <= 0 ? 0 : maxY - minY + 1;
        }

        public int boundingDepth() {
            return voxelCount <= 0 ? 0 : maxZ - minZ + 1;
        }

        /**
         * Looks up one feature by the name {@link sc.fiji.oc3d.core.api.MorphPredicate}
         * uses, so filtering does not need a switch at every call site.
         *
         * @return {@code NaN} for an unknown name - the predicate's own
         *         unknown-feature rule then lets the object through
         */
        public double feature(String name) {
            if (name == null) return Double.NaN;
            String key = name.trim();
            if ("volume".equals(key)) return voxelCount;
            if ("volume_calibrated".equals(key)) return calibratedVolume;
            if ("surface_area".equals(key)) return surfaceArea;
            if ("sphericity".equals(key)) return sphericity();
            if ("compactness".equals(key)) return compactness();
            if ("elongation".equals(key)) return elongation();
            if ("mean_intensity".equals(key)) return intensityMean();
            if ("max_intensity".equals(key)) return intensityMax();
            if ("feret_diameter_max".equals(key)) return feretDiameterMax();
            return Double.NaN;
        }

        /**
         * Whether the intensity total can carry a centre of mass.
         *
         * <p>Requires a finite, <em>strictly positive</em> total. Zero has no
         * usable denominator, and a negative one is worse than useless: it
         * flips the sign of every offset from the origin, so the "centre of
         * mass" of a background-subtracted object can land outside the object,
         * outside the image, and still be reported as a coordinate. Falling
         * back to the geometric centroid is the answer that stays inside the
         * object's own convex hull.
         *
         * <p>A negative total is not exotic. Any background-subtracted or
         * ratiometric 32-bit stack can produce one for a dim object.
         */
        private boolean hasWeightedCenter() {
            return hasIntensityValues() && Double.isFinite(intensitySum) && intensitySum > 0.0;
        }

        private void addFeretPoint(double x, double y, double z) {
            if (feretMin == null) {
                feretMin = new double[FERET_DIRECTIONS.length];
                feretMax = new double[FERET_DIRECTIONS.length];
                Arrays.fill(feretMin, Double.POSITIVE_INFINITY);
                Arrays.fill(feretMax, Double.NEGATIVE_INFINITY);
            }
            for (int i = 0; i < FERET_DIRECTIONS.length; i++) {
                double[] direction = FERET_DIRECTIONS[i];
                double projection = x * direction[0] + y * direction[1] + z * direction[2];
                if (projection < feretMin[i]) feretMin[i] = projection;
                if (projection > feretMax[i]) feretMax[i] = projection;
            }
        }

        private double computeFeretDiameterMax() {
            if (feretMin == null || feretMax == null) return Double.NaN;
            double maxSpan = 0.0;
            for (int i = 0; i < feretMin.length; i++) {
                if (!Double.isFinite(feretMin[i]) || !Double.isFinite(feretMax[i])) continue;
                double span = feretMax[i] - feretMin[i];
                if (span > maxSpan) {
                    maxSpan = span;
                }
            }
            return maxSpan;
        }

        /**
         * Eigenvalues of the calibrated covariance matrix, ascending, or {@code null}
         * for an object too small to have one. Computed once and shared: elongation,
         * flatness and spareness are all ratios of the same principal axes, and
         * computing them separately invited them to disagree.
         */
        private double[] shapeEigenvalues() {
            if (voxelCount <= 1) return null;
            double invCount = 1.0 / (double) voxelCount;
            double cx = shapeXSum * invCount;
            double cy = shapeYSum * invCount;
            double cz = shapeZSum * invCount;
            double cxx = shapeXXSum * invCount - cx * cx;
            double cyy = shapeYYSum * invCount - cy * cy;
            double czz = shapeZZSum * invCount - cz * cz;
            double cxy = shapeXYSum * invCount - cx * cy;
            double cxz = shapeXZSum * invCount - cx * cz;
            double cyz = shapeYZSum * invCount - cy * cz;
            double[] eigenvalues = symmetricEigenvalues3x3(cxx, cxy, cxz, cyy, cyz, czz);
            Arrays.sort(eigenvalues);
            return eigenvalues;
        }

        private double computeElongation(double[] eigenvalues) {
            if (eigenvalues == null) return Double.NaN;
            double smallest = zeroIfTiny(eigenvalues[0]);
            double largest = zeroIfTiny(eigenvalues[2]);
            if (largest <= 0.0 || smallest <= 0.0) return Double.NaN;
            return Math.sqrt(largest / smallest);
        }

        /**
         * Ratio of the middle principal axis to the shortest, so a needle is 1 and a
         * pancake is large. Elongation is the same ratio taken over the longest and
         * shortest axes, which is why both are needed to separate the two shapes -
         * that separation is exactly what {@code Morph_MP} reports.
         */
        private double computeFlatness(double[] eigenvalues) {
            if (eigenvalues == null) return Double.NaN;
            double smallest = zeroIfTiny(eigenvalues[0]);
            double middle = zeroIfTiny(eigenvalues[1]);
            if (middle <= 0.0 || smallest <= 0.0) return Double.NaN;
            return Math.sqrt(middle / smallest);
        }

        /**
         * Object volume over the volume of the ellipsoid with the same principal
         * moments: 1 for a solid ellipsoid, below 1 for anything that leaves the
         * ellipsoid it fits inside partly empty.
         *
         * <p>A solid uniform ellipsoid with semi-axis {@code a} has variance
         * {@code a^2 / 5} along that axis, so {@code a = sqrt(5 * lambda)}, and its
         * volume is {@code 4/3 * pi * a * b * c}.
         *
         * <p><b>This is analytic, and mcib3d's {@code ELL_SPARENESS} is not.</b> That
         * implementation rasterises a fitted ellipsoid with
         * {@code ObjectCreator3D.createEllipsoidAxesUnit} and divides voxel counts -
         * read from the shipped bytecode, not assumed - so it carries a discretisation
         * error that depends on the object's size and orientation. The two agree in the
         * limit and differ on small objects, which is a difference of method, not a
         * defect, and it is why {@code Morph_PB} is expected to move when this replaces
         * the mcib3d call.
         */
        private double computeSpareness(double[] eigenvalues) {
            if (eigenvalues == null || !(calibratedVolume > 0.0)) return Double.NaN;
            double smallest = zeroIfTiny(eigenvalues[0]);
            double middle = zeroIfTiny(eigenvalues[1]);
            double largest = zeroIfTiny(eigenvalues[2]);
            if (smallest <= 0.0 || middle <= 0.0 || largest <= 0.0) return Double.NaN;
            double semiA = Math.sqrt(5.0 * largest);
            double semiB = Math.sqrt(5.0 * middle);
            double semiC = Math.sqrt(5.0 * smallest);
            double ellipsoidVolume = 4.0 / 3.0 * Math.PI * semiA * semiB * semiC;
            if (!(ellipsoidVolume > 0.0)) return Double.NaN;
            return calibratedVolume / ellipsoidVolume;
        }

        /** Distance from the object's centroid to one of its surface voxels. */
        private void addSurfaceDistance(double px, double py, double pz) {
            if (voxelCount <= 0) return;
            double invCount = 1.0 / (double) voxelCount;
            double dx = px - shapeXSum * invCount;
            double dy = py - shapeYSum * invCount;
            double dz = pz - shapeZSum * invCount;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            surfaceDistanceSum += distance;
            surfaceDistanceSumSquares += distance * distance;
            surfaceDistanceCount++;
        }
    }

    private static double[] symmetricEigenvalues3x3(double cxx,
                                                    double cxy,
                                                    double cxz,
                                                    double cyy,
                                                    double cyz,
                                                    double czz) {
        double p1 = cxy * cxy + cxz * cxz + cyz * cyz;
        if (p1 == 0.0) {
            return new double[] {cxx, cyy, czz};
        }

        double q = (cxx + cyy + czz) / 3.0;
        double axx = cxx - q;
        double ayy = cyy - q;
        double azz = czz - q;
        double p2 = axx * axx + ayy * ayy + azz * azz + 2.0 * p1;
        double p = Math.sqrt(p2 / 6.0);
        if (!Double.isFinite(p) || p <= 0.0) {
            return new double[] {cxx, cyy, czz};
        }

        double bxx = axx / p;
        double byy = ayy / p;
        double bzz = azz / p;
        double bxy = cxy / p;
        double bxz = cxz / p;
        double byz = cyz / p;
        double determinant = bxx * (byy * bzz - byz * byz)
                - bxy * (bxy * bzz - byz * bxz)
                + bxz * (bxy * byz - byy * bxz);
        double r = determinant / 2.0;

        double phi;
        if (r <= -1.0) {
            phi = Math.PI / 3.0;
        } else if (r >= 1.0) {
            phi = 0.0;
        } else {
            phi = Math.acos(r) / 3.0;
        }

        double largest = q + 2.0 * p * Math.cos(phi);
        double smallest = q + 2.0 * p * Math.cos(phi + (2.0 * Math.PI / 3.0));
        double middle = 3.0 * q - largest - smallest;
        return new double[] {largest, middle, smallest};
    }

    private static double zeroIfTiny(double value) {
        if (!Double.isFinite(value)) return Double.NaN;
        return Math.abs(value) <= EIGENVALUE_ZERO_TOLERANCE ? 0.0 : value;
    }

    /**
     * Per-label storage that starts dense and degrades to a map.
     *
     * <p>A dense array is the right shape for the common case - labels are
     * {@code 1..N} with no holes - but a label image with a few very large
     * labels would allocate an array sized by the largest one. Above
     * {@link #MAX_DENSE_LABEL_PROPERTY}, or on an allocation failure, storage
     * switches to a {@code HashMap} and keeps going rather than throwing
     * {@code OutOfMemoryError} out of the plugin.
     */
    private static final class FeatureValuesByLabel {
        private final int maxDenseLabel;
        private FeatureValues[] dense;
        private Map<Integer, FeatureValues> sparse;

        /** Median histogram bins for this run, or 0 to retain exact values. */
        private final int histogramBins;

        FeatureValuesByLabel(int histogramBins) {
            this.histogramBins = histogramBins;
            maxDenseLabel = configuredMaxDenseLabel();
            if (maxDenseLabel > 0) {
                int initialLength = (int) Math.min((long) INITIAL_DENSE_LABELS,
                        (long) maxDenseLabel + 1L);
                dense = new FeatureValues[Math.max(1, initialLength)];
            } else {
                sparse = new HashMap<Integer, FeatureValues>();
            }
        }

        FeatureValues get(int label) {
            if (label <= 0) return null;
            if (dense != null) {
                return label < dense.length ? dense[label] : null;
            }
            return sparse == null ? null : sparse.get(Integer.valueOf(label));
        }

        FeatureValues getOrCreate(int label) {
            if (label <= 0) return null;
            if (dense != null && label <= maxDenseLabel) {
                if (ensureDenseCapacity(label)) {
                    FeatureValues values = dense[label];
                    if (values == null) {
                        values = new FeatureValues(label, histogramBins);
                        dense[label] = values;
                    }
                    return values;
                }
            }
            switchToSparse();
            Integer key = Integer.valueOf(label);
            FeatureValues values = sparse.get(key);
            if (values == null) {
                values = new FeatureValues(label, histogramBins);
                sparse.put(key, values);
            }
            return values;
        }

        void finish(CalibrationScales scales) {
            List<FeatureValues> values = values();
            for (int i = 0; i < values.size(); i++) {
                values.get(i).finish(scales);
            }
        }

        List<Integer> labelsSorted() {
            List<Integer> labels = new ArrayList<Integer>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    if (dense[label] != null) {
                        labels.add(Integer.valueOf(label));
                    }
                }
            } else if (sparse != null) {
                labels.addAll(sparse.keySet());
            }
            Collections.sort(labels);
            return labels;
        }

        boolean usesSparseStorage() {
            return sparse != null;
        }

        private List<FeatureValues> values() {
            List<FeatureValues> values = new ArrayList<FeatureValues>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    if (dense[label] != null) {
                        values.add(dense[label]);
                    }
                }
            } else if (sparse != null) {
                values.addAll(sparse.values());
            }
            return values;
        }

        private boolean ensureDenseCapacity(int label) {
            if (label < dense.length) return true;
            long targetLength = Math.min((long) maxDenseLabel + 1L,
                    Math.max((long) label + 1L, (long) dense.length * 2L));
            while (targetLength <= label && targetLength <= maxDenseLabel) {
                targetLength *= 2L;
            }
            if (targetLength > Integer.MAX_VALUE) return false;
            try {
                dense = Arrays.copyOf(dense, (int) targetLength);
                return true;
            } catch (OutOfMemoryError oom) {
                switchToSparse();
                System.gc();
                return false;
            }
        }

        private void switchToSparse() {
            if (sparse != null) return;
            Map<Integer, FeatureValues> replacement = new HashMap<Integer, FeatureValues>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    FeatureValues values = dense[label];
                    if (values != null) {
                        replacement.put(Integer.valueOf(label), values);
                    }
                }
            }
            sparse = replacement;
            dense = null;
        }
    }

    private static final class CalibrationScales {
        final String unit;
        final double pixelWidth;
        final double pixelHeight;
        final double pixelDepth;
        final double voxelVolume;
        final double yzFaceArea;
        final double xzFaceArea;
        final double xyFaceArea;

        private CalibrationScales(String unit,
                                  double pixelWidth,
                                  double pixelHeight,
                                  double pixelDepth) {
            this.unit = unit;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.pixelDepth = pixelDepth;
            voxelVolume = pixelWidth * pixelHeight * pixelDepth;
            yzFaceArea = pixelHeight * pixelDepth;
            xzFaceArea = pixelWidth * pixelDepth;
            xyFaceArea = pixelWidth * pixelHeight;
        }

        static CalibrationScales from(Calibration calibration) {
            return new CalibrationScales(unitOf(calibration),
                    calibration == null ? 1.0 : positiveOrOne(calibration.pixelWidth),
                    calibration == null ? 1.0 : positiveOrOne(calibration.pixelHeight),
                    calibration == null ? 1.0 : positiveOrOne(calibration.pixelDepth));
        }
    }
}
