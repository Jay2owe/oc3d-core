package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sc.fiji.oc3d.core.label.LabelImages;

/**
 * Per-label centroids and voxel counts, and nothing else.
 *
 * <p>{@link LabelFeatureAccumulator} answers the same question, plus surface
 * area, sphericity, elongation, median intensity and a thirteen-direction Feret
 * estimate. Every one of those costs work per voxel - the Feret pass alone
 * projects each voxel thirteen times - and a caller that only needs to know
 * where each object sits pays all of it for nothing.
 *
 * <p>So this is the lean path. What it is <em>not</em> is a second opinion:
 * membership comes from {@link LabelImages#labelFromPixel}, ordering is
 * ascending label, and the arithmetic is the same sum over the same count. That
 * the two agree exactly is asserted by test rather than asserted in prose,
 * because a plugin whose measurement half and coincidence half disagreed about
 * which voxels belong to object 7 would be wrong in a way no user could see.
 *
 * <p>When the caller already holds a {@link LabelFeatureAccumulator.Result} -
 * as any plugin that has just measured its objects does - it should not call
 * this at all. {@link #from(LabelFeatureAccumulator.Result, boolean)} reuses
 * that scan and touches no pixels.
 */
public final class CentroidScan {

    private CentroidScan() {
        // Utility class.
    }

    /** One object's position, in pixel coordinates. */
    public static final class Centroid {

        private final int label;
        private final double x;
        private final double y;
        private final double z;
        private final long voxelCount;

        Centroid(int label, double x, double y, double z, long voxelCount) {
            this.label = label;
            this.x = x;
            this.y = y;
            this.z = z;
            this.voxelCount = voxelCount;
        }

        public int label() {
            return label;
        }

        /** Column index, in pixels. Never calibrated - a lookup needs pixels. */
        public double x() {
            return x;
        }

        /** Row index, in pixels. */
        public double y() {
            return y;
        }

        /** Zero-based slice index. */
        public double z() {
            return z;
        }

        public long voxelCount() {
            return voxelCount;
        }

        @Override
        public String toString() {
            return "Centroid[label=" + label + " x=" + x + " y=" + y + " z=" + z
                    + " voxels=" + voxelCount + "]";
        }
    }

    /** Centroids for every label in an image, ascending by label. */
    public static final class Result {

        private final List<Centroid> centroids;
        private final Map<Integer, Centroid> byLabel;

        Result(List<Centroid> centroids) {
            this.centroids = Collections.unmodifiableList(centroids);
            Map<Integer, Centroid> index = new LinkedHashMap<Integer, Centroid>();
            for (Centroid centroid : centroids) {
                index.put(Integer.valueOf(centroid.label()), centroid);
            }
            this.byLabel = Collections.unmodifiableMap(index);
        }

        /** Ascending by label — the order every table in the family uses. */
        public List<Centroid> centroids() {
            return centroids;
        }

        /** The centroid for one label, or null when the label is absent. */
        public Centroid forLabel(int label) {
            return byLabel.get(Integer.valueOf(label));
        }

        public int objectCount() {
            return centroids.size();
        }
    }

    /** Geometric centroids of every label in {@code labelImage}. */
    public static Result scan(ImagePlus labelImage) {
        return scan(labelImage, null);
    }

    /**
     * Centroids of every label, weighted by intensity when an image is given.
     *
     * <p>The weighted centre needs a finite, strictly positive intensity total;
     * anything else falls back to the geometric centroid for that object, which
     * is the same rule {@link LabelFeatureAccumulator} applies. Non-finite
     * samples are skipped rather than accumulated, so one NaN voxel cannot turn
     * a whole object's position into NaN.
     *
     * @param labelImage     0, negative and non-finite values are background
     * @param intensityImage optional weights; must match {@code labelImage} in
     *                       width, height and slice count
     * @throws IllegalArgumentException naming the offending image and dimension
     */
    public static Result scan(ImagePlus labelImage, ImagePlus intensityImage) {
        if (labelImage == null) {
            throw new IllegalArgumentException(
                    "labelImage must not be null (labelImage=null; expected an ImagePlus).");
        }
        ImageStack labelStack = labelImage.getStack();
        if (labelStack == null) {
            throw new IllegalArgumentException(
                    "labelImage must have a stack (labelImage='" + labelImage.getTitle()
                            + "'; getStack() returned null).");
        }
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelStack.getSize();

        ImageStack intensityStack = null;
        if (intensityImage != null) {
            intensityStack = intensityImage.getStack();
            if (intensityImage.getWidth() != width
                    || intensityImage.getHeight() != height
                    || intensityStack == null
                    || intensityStack.getSize() != depth) {
                throw new IllegalArgumentException(
                        "intensityImage must match labelImage (labelImage="
                                + width + "x" + height + "x" + depth
                                + "; intensityImage=" + intensityImage.getWidth() + "x"
                                + intensityImage.getHeight() + "x"
                                + (intensityStack == null ? 0 : intensityStack.getSize()) + ").");
            }
        }

        Map<Integer, Sums> sumsByLabel = new LinkedHashMap<Integer, Sums>();
        for (int z = 0; z < depth; z++) {
            ImageProcessor labelProcessor = labelStack.getProcessor(z + 1);
            ImageProcessor intensityProcessor = intensityStack == null
                    ? null : intensityStack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = offset + x;
                    int label = LabelImages.labelFromPixel(labelProcessor.getf(index));
                    if (label <= 0) continue;
                    Integer key = Integer.valueOf(label);
                    Sums sums = sumsByLabel.get(key);
                    if (sums == null) {
                        sums = new Sums();
                        sumsByLabel.put(key, sums);
                    }
                    sums.addVoxel(x, y, z);
                    if (intensityProcessor != null) {
                        float intensity = intensityProcessor.getf(index);
                        if (isFinite(intensity)) sums.addIntensity(intensity, x, y, z);
                    }
                }
            }
        }

        List<Integer> labels = new ArrayList<Integer>(sumsByLabel.keySet());
        Collections.sort(labels);
        List<Centroid> centroids = new ArrayList<Centroid>(labels.size());
        for (Integer label : labels) {
            centroids.add(sumsByLabel.get(label).toCentroid(label.intValue()));
        }
        return new Result(centroids);
    }

    /**
     * Reuses a measurement pass that has already happened.
     *
     * <p>This is the entry point for a plugin that has just measured its
     * objects and now wants to know which of them coincide with something. It
     * reads no pixels, so the coincidence answer cannot disagree with the
     * measurement table it will be printed next to.
     *
     * @param result         a completed measurement scan
     * @param intensityWeighted use the centre of mass rather than the
     *                          geometric centroid
     */
    public static Result from(LabelFeatureAccumulator.Result result, boolean intensityWeighted) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null (result=null).");
        }
        List<Centroid> centroids = new ArrayList<Centroid>(result.objectCount());
        for (Integer label : result.labelsSorted()) {
            LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(label.intValue());
            centroids.add(new Centroid(label.intValue(),
                    intensityWeighted ? values.centerOfMassX() : values.centroidX(),
                    intensityWeighted ? values.centerOfMassY() : values.centroidY(),
                    intensityWeighted ? values.centerOfMassZ() : values.centroidZ(),
                    values.voxelCount()));
        }
        return new Result(centroids);
    }

    /** {@code Float.isFinite} is Java 8; this module still targets earlier. */
    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** Running totals for one label. */
    private static final class Sums {

        private long voxelCount;
        private double xSum;
        private double ySum;
        private double zSum;

        private long intensityCount;
        private double intensitySum;
        private double weightedX;
        private double weightedY;
        private double weightedZ;

        void addVoxel(double x, double y, double z) {
            voxelCount++;
            xSum += x;
            ySum += y;
            zSum += z;
        }

        void addIntensity(double intensity, double x, double y, double z) {
            intensityCount++;
            intensitySum += intensity;
            weightedX += intensity * x;
            weightedY += intensity * y;
            weightedZ += intensity * z;
        }

        Centroid toCentroid(int label) {
            double geometricX = voxelCount <= 0 ? Double.NaN : xSum / (double) voxelCount;
            double geometricY = voxelCount <= 0 ? Double.NaN : ySum / (double) voxelCount;
            double geometricZ = voxelCount <= 0 ? Double.NaN : zSum / (double) voxelCount;
            boolean weighted = intensityCount > 0
                    && !Double.isNaN(intensitySum)
                    && !Double.isInfinite(intensitySum)
                    && intensitySum > 0.0;
            return new Centroid(label,
                    weighted ? weightedX / intensitySum : geometricX,
                    weighted ? weightedY / intensitySum : geometricY,
                    weighted ? weightedZ / intensitySum : geometricZ,
                    voxelCount);
        }
    }
}
