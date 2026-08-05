package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The agreement contract.
 * <p>
 * {@link CentroidScan} exists to be cheaper than {@link LabelFeatureAccumulator},
 * not to be different from it. Every assertion here compares the two on the same
 * input, because the moment they disagree, a plugin that measures with one and
 * tests coincidence with the other reports two incompatible views of the same
 * objects in the same table.
 */
public class CentroidScanTest {

    @Test
    public void agreesWithTheMeasurementPathOnGeometricCentroids() {
        ImagePlus labels = labels(new int[][][]{
                {{0, 0, 0, 0, 0},
                 {0, 1, 1, 0, 2},
                 {0, 1, 0, 0, 2},
                 {0, 0, 0, 0, 0}},
                {{3, 0, 0, 0, 0},
                 {3, 3, 0, 0, 2},
                 {0, 0, 0, 0, 0},
                 {0, 0, 0, 7, 7}}});

        assertAgrees(labels, null, false);
    }

    @Test
    public void agreesWithTheMeasurementPathOnCentresOfMass() {
        ImagePlus labels = labels(new int[][][]{
                {{1, 1, 0, 2, 2},
                 {1, 1, 0, 2, 2}}});
        ImagePlus raw = intensity(new float[][][]{
                {{9f, 1f, 0f, 4f, 4f},
                 {1f, 1f, 0f, 4f, 4f}}});

        assertAgrees(labels, raw, true);
    }

    /**
     * Ascending label, not first-voxel-encounter. Label 9 is touched before
     * label 1 here, which is the only arrangement that can tell the two apart.
     */
    @Test
    public void ordersByLabelNotByFirstAppearance() {
        ImagePlus labels = labels(new int[][][]{
                {{9, 9, 0, 5, 5},
                 {0, 0, 0, 0, 0},
                 {7, 7, 0, 1, 1}}});

        List<CentroidScan.Centroid> centroids = CentroidScan.scan(labels).centroids();

        assertEquals(4, centroids.size());
        assertEquals(1, centroids.get(0).label());
        assertEquals(5, centroids.get(1).label());
        assertEquals(7, centroids.get(2).label());
        assertEquals(9, centroids.get(3).label());
        assertAgrees(labels, null, false);
    }

    @Test
    public void nonIntegerLabelsRoundRatherThanTruncate() {
        ImagePlus labels = floatLabels(new float[][][]{
                {{2.7f, 2.7f, 0f, 1.2f},
                 {-3f, Float.NaN, 0f, 1.2f}}});

        CentroidScan.Result result = CentroidScan.scan(labels);

        // 2.7 rounds to 3, 1.2 rounds to 1; negative and NaN are background.
        assertEquals(2, result.objectCount());
        assertEquals(1, result.centroids().get(0).label());
        assertEquals(3, result.centroids().get(1).label());
        assertNull(result.forLabel(2));
        assertAgrees(labels, null, false);
    }

    /**
     * A negative intensity total has no usable denominator. Dividing by it
     * would move the centre of mass to the far side of the origin.
     */
    @Test
    public void negativeIntensityTotalFallsBackToTheGeometricCentroid() {
        ImagePlus labels = labels(new int[][][]{{{1, 1}, {1, 1}}});
        ImagePlus raw = intensity(new float[][][]{{{1f, -4f}, {-4f, -4f}}});

        CentroidScan.Centroid weighted = CentroidScan.scan(labels, raw).centroids().get(0);
        CentroidScan.Centroid geometric = CentroidScan.scan(labels).centroids().get(0);

        assertEquals(geometric.x(), weighted.x(), 0.0);
        assertEquals(geometric.y(), weighted.y(), 0.0);
        assertAgrees(labels, raw, true);
    }

    @Test
    public void zeroIntensityTotalFallsBackToTheGeometricCentroid() {
        ImagePlus labels = labels(new int[][][]{{{1, 1}, {1, 1}}});
        ImagePlus raw = intensity(new float[][][]{{{0f, 0f}, {0f, 0f}}});

        CentroidScan.Centroid weighted = CentroidScan.scan(labels, raw).centroids().get(0);

        assertEquals(0.5, weighted.x(), 0.0);
        assertEquals(0.5, weighted.y(), 0.0);
        assertAgrees(labels, raw, true);
    }

    /** One NaN voxel must not turn a whole object's position into NaN. */
    @Test
    public void nonFiniteIntensitiesAreSkippedNotAccumulated() {
        ImagePlus labels = labels(new int[][][]{{{1, 1, 1}, {1, 1, 1}}});
        ImagePlus raw = intensity(new float[][][]{
                {{2f, Float.NaN, 2f},
                 {2f, Float.POSITIVE_INFINITY, 2f}}});

        CentroidScan.Centroid weighted = CentroidScan.scan(labels, raw).centroids().get(0);

        assertTrue("centroid went non-finite: " + weighted,
                !Double.isNaN(weighted.x()) && !Double.isNaN(weighted.y()));
        assertAgrees(labels, raw, true);
    }

    @Test
    public void reusesACompletedMeasurementScanWithoutTouchingPixels() {
        ImagePlus labels = labels(new int[][][]{
                {{1, 1, 0, 2},
                 {1, 0, 0, 2}}});
        ImagePlus raw = intensity(new float[][][]{
                {{5f, 1f, 0f, 3f},
                 {1f, 0f, 0f, 9f}}});

        CentroidScan.Result reused = CentroidScan.from(
                LabelFeatureAccumulator.scan(labels, raw), true);
        CentroidScan.Result scanned = CentroidScan.scan(labels, raw);

        assertEquals(scanned.objectCount(), reused.objectCount());
        for (int i = 0; i < scanned.objectCount(); i++) {
            assertEquals(scanned.centroids().get(i).label(), reused.centroids().get(i).label());
            assertEquals(scanned.centroids().get(i).x(), reused.centroids().get(i).x(), 0.0);
            assertEquals(scanned.centroids().get(i).y(), reused.centroids().get(i).y(), 0.0);
            assertEquals(scanned.centroids().get(i).z(), reused.centroids().get(i).z(), 0.0);
            assertEquals(scanned.centroids().get(i).voxelCount(),
                    reused.centroids().get(i).voxelCount());
        }
    }

    @Test
    public void emptyImageYieldsNoObjects() {
        assertEquals(0, CentroidScan.scan(labels(new int[][][]{{{0, 0}, {0, 0}}})).objectCount());
    }

    @Test
    public void mismatchedIntensityImageIsRejectedByName() {
        try {
            CentroidScan.scan(labels(new int[][][]{{{1, 1}}}),
                    intensity(new float[][][]{{{1f, 1f, 1f}}}));
            fail("Expected a dimension mismatch to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("intensityImage must match labelImage"));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Bit-for-bit against the measurement path, which is the whole point. */
    private static void assertAgrees(ImagePlus labels, ImagePlus raw, boolean weighted) {
        CentroidScan.Result lean = CentroidScan.scan(labels, raw);
        LabelFeatureAccumulator.Result full = LabelFeatureAccumulator.scan(labels, raw);

        assertEquals("object count", full.objectCount(), lean.objectCount());
        List<Integer> labelsSorted = full.labelsSorted();
        for (int i = 0; i < labelsSorted.size(); i++) {
            int label = labelsSorted.get(i).intValue();
            CentroidScan.Centroid centroid = lean.centroids().get(i);
            LabelFeatureAccumulator.FeatureValues values = full.valuesForLabel(label);

            assertEquals("label order at " + i, label, centroid.label());
            assertEquals("voxelCount for " + label, values.voxelCount(), centroid.voxelCount());
            assertEquals("x for " + label,
                    weighted ? values.centerOfMassX() : values.centroidX(), centroid.x(), 0.0);
            assertEquals("y for " + label,
                    weighted ? values.centerOfMassY() : values.centroidY(), centroid.y(), 0.0);
            assertEquals("z for " + label,
                    weighted ? values.centerOfMassZ() : values.centroidZ(), centroid.z(), 0.0);
        }
    }

    private static ImagePlus labels(int[][][] values) {
        int depth = values.length;
        int height = values[0].length;
        int width = values[0][0].length;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) processor.set(x, y, values[z][y][x]);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus floatLabels(float[][][] values) {
        return intensity(values);
    }

    private static ImagePlus intensity(float[][][] values) {
        int depth = values.length;
        int height = values[0].length;
        int width = values[0][0].length;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            FloatProcessor processor = new FloatProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) processor.setf(x, y, values[z][y][x]);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("intensity", stack);
    }
}
