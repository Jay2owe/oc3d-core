package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Two properties of the Feret estimate that hold without mcib3d on the classpath, so
 * they survive the dependency removal that the reference comparisons will not.
 *
 * <ol>
 * <li><b>Accumulating over surface voxels only is lossless.</b> The voxel maximising
 *     the projection onto a direction {@code d} must have an exposed face on the axis
 *     best aligned with {@code d} - otherwise its same-label neighbour along that axis
 *     would project further - so no extremal voxel is ever skipped. Checked against a
 *     reference that projects every foreground voxel.</li>
 * <li><b>The estimate is bracketed by the exact value and the declared bound.</b> For
 *     small objects the exact maximum pairwise distance is computable by brute force,
 *     which needs no external library.</li>
 * </ol>
 */
public class FeretSurfaceRestrictionTest {

    /** {@code 1 - cos(15.10 degrees)} for the 64-direction set; see FeretDirectionsTest. */
    private static final double DECLARED_WORST_UNDER_ESTIMATE = 0.0345;

    private static final long SEED = 20260806L;

    private static final Calibration ISOTROPIC = calibration(1.0, 1.0, 1.0);

    /** The lab's confocal geometry: fine in xy, coarse in z. */
    private static final Calibration ANISOTROPIC = calibration(0.28409, 0.28409, 0.99993);

    @Test
    public void surfaceVoxelsGiveTheSameFeretAsEveryVoxel() {
        Random random = new Random(SEED);
        for (int shape = 0; shape < 60; shape++) {
            ImagePlus labels = randomShape(random, shape);
            Calibration calibration = shape % 2 == 0 ? ISOTROPIC : ANISOTROPIC;
            try {
                LabelFeatureAccumulator.Result result =
                        LabelFeatureAccumulator.scan(labels, null, calibration);
                LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);
                if (values == null) continue;
                // Exact equality, not a tolerance: both sides take a maximum over the
                // same directions of the same arithmetic, and the maximum is attained
                // at a voxel present in both sets. A difference here is a missed
                // extremal voxel, which no tolerance should hide.
                assertEquals("shape " + shape + " lost an extremal voxel",
                        feretOverEveryVoxel(labels, calibration),
                        values.feretDiameterMax(), 0.0);
            } finally {
                labels.flush();
            }
        }
    }

    @Test
    public void theEstimateNeverExceedsTheExactPairwiseValue() {
        Random random = new Random(SEED + 1);
        for (int shape = 0; shape < 40; shape++) {
            ImagePlus labels = randomShape(random, shape);
            Calibration calibration = shape % 2 == 0 ? ISOTROPIC : ANISOTROPIC;
            try {
                LabelFeatureAccumulator.FeatureValues values =
                        LabelFeatureAccumulator.scan(labels, null, calibration).valuesForLabel(1);
                if (values == null) continue;
                double exact = exactFeret(labels, calibration);
                if (!(exact > 0.0)) continue;
                double estimate = values.feretDiameterMax();
                assertTrue("shape " + shape + " over-estimated: estimate=" + estimate
                                + " exact=" + exact,
                        estimate <= exact * (1.0 + 1e-12));
            } finally {
                labels.flush();
            }
        }
    }

    @Test
    public void theEstimateIsWithinTheDeclaredBoundOfExact() {
        Random random = new Random(SEED + 2);
        double worst = 0.0;
        int shapeOfWorst = -1;
        for (int shape = 0; shape < 40; shape++) {
            ImagePlus labels = randomShape(random, shape);
            Calibration calibration = shape % 2 == 0 ? ISOTROPIC : ANISOTROPIC;
            try {
                LabelFeatureAccumulator.FeatureValues values =
                        LabelFeatureAccumulator.scan(labels, null, calibration).valuesForLabel(1);
                if (values == null) continue;
                double exact = exactFeret(labels, calibration);
                if (!(exact > 0.0)) continue;
                double under = (exact - values.feretDiameterMax()) / exact;
                if (under > worst) {
                    worst = under;
                    shapeOfWorst = shape;
                }
            } finally {
                labels.flush();
            }
        }
        assertTrue("worst under-estimate " + worst + " (shape " + shapeOfWorst
                        + ") exceeds the declared bound " + DECLARED_WORST_UNDER_ESTIMATE,
                worst <= DECLARED_WORST_UNDER_ESTIMATE);
    }

    // ── references ───────────────────────────────────────────────────────

    /** The same computation the accumulator does, but over every foreground voxel. */
    private static double feretOverEveryVoxel(ImagePlus labels, Calibration calibration) {
        double[][] directions = LabelFeatureAccumulator.FERET_DIRECTIONS;
        double[] min = new double[directions.length];
        double[] max = new double[directions.length];
        for (int i = 0; i < directions.length; i++) {
            min[i] = Double.POSITIVE_INFINITY;
            max[i] = Double.NEGATIVE_INFINITY;
        }
        List<double[]> points = points(labels, calibration);
        for (int p = 0; p < points.size(); p++) {
            double[] point = points.get(p);
            for (int i = 0; i < directions.length; i++) {
                double projection = point[0] * directions[i][0]
                        + point[1] * directions[i][1]
                        + point[2] * directions[i][2];
                if (projection < min[i]) min[i] = projection;
                if (projection > max[i]) max[i] = projection;
            }
        }
        double best = 0.0;
        for (int i = 0; i < directions.length; i++) {
            if (!isFinite(min[i]) || !isFinite(max[i])) continue;
            double span = max[i] - min[i];
            if (span > best) best = span;
        }
        return best;
    }

    /** Brute-force maximum pairwise distance between voxel centres. */
    private static double exactFeret(ImagePlus labels, Calibration calibration) {
        List<double[]> points = points(labels, calibration);
        double best = 0.0;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dx = points.get(i)[0] - points.get(j)[0];
                double dy = points.get(i)[1] - points.get(j)[1];
                double dz = points.get(i)[2] - points.get(j)[2];
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance > best) best = distance;
            }
        }
        return best;
    }

    private static List<double[]> points(ImagePlus labels, Calibration calibration) {
        List<double[]> out = new ArrayList<double[]>();
        ImageStack stack = labels.getStack();
        for (int z = 0; z < stack.getSize(); z++) {
            for (int y = 0; y < labels.getHeight(); y++) {
                for (int x = 0; x < labels.getWidth(); x++) {
                    if (stack.getProcessor(z + 1).get(x, y) <= 0) continue;
                    out.add(new double[] {
                            x * calibration.pixelWidth,
                            y * calibration.pixelHeight,
                            z * calibration.pixelDepth});
                }
            }
        }
        return out;
    }

    // ── shapes ───────────────────────────────────────────────────────────

    /**
     * A branched walk, which is the shape class that matters: a straight rod's extreme
     * pair lies along its own axis, but a branched object's lies between two arm tips
     * in a direction nothing about the object announces.
     */
    private static ImagePlus randomShape(Random random, int index) {
        int size = 14 + random.nextInt(10);
        int depth = 6 + random.nextInt(8);
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < depth; z++) stack.addSlice(new ShortProcessor(size, size));
        ImagePlus image = new ImagePlus("feret-shape-" + index, stack);

        int branches = 1 + random.nextInt(3);
        for (int branch = 0; branch < branches; branch++) {
            int x = size / 2;
            int y = size / 2;
            int z = depth / 2;
            int steps = 4 + random.nextInt(size);
            int dx = random.nextInt(3) - 1;
            int dy = random.nextInt(3) - 1;
            int dz = random.nextInt(3) - 1;
            if (dx == 0 && dy == 0 && dz == 0) dx = 1;
            for (int step = 0; step < steps; step++) {
                if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= depth) break;
                stack.getProcessor(z + 1).set(x, y, 1);
                // A one-voxel-wide walk would make every voxel a surface voxel, which
                // would not exercise the restriction at all. Thicken sometimes.
                if (random.nextBoolean() && x + 1 < size) stack.getProcessor(z + 1).set(x + 1, y, 1);
                if (random.nextBoolean() && y + 1 < size) stack.getProcessor(z + 1).set(x, y + 1, 1);
                x += dx;
                y += dy;
                z += dz;
                if (random.nextInt(5) == 0) {
                    dx = random.nextInt(3) - 1;
                    dy = random.nextInt(3) - 1;
                    dz = random.nextInt(3) - 1;
                    if (dx == 0 && dy == 0 && dz == 0) dy = 1;
                }
            }
        }
        return image;
    }

    private static Calibration calibration(double width, double height, double depth) {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = width;
        calibration.pixelHeight = height;
        calibration.pixelDepth = depth;
        calibration.setUnit("micron");
        return calibration;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
