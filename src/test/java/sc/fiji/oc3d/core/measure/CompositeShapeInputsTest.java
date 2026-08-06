package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The four inputs the composite shape indices need, checked against shapes whose
 * answers are known analytically rather than against another implementation.
 *
 * <p>They exist because {@code 3D Objects Counter+} read them from mcib3d, in one
 * shipped file that the migration plan says does not exist, and that dependency is
 * what stands between the plugin and a BSD licence. Reproducing mcib3d's numbers
 * exactly is not the goal and for spareness is not possible - it rasterises a fitted
 * ellipsoid and divides voxel counts - so the target is a stated definition that a
 * reader can check, and tests that pin it to geometry.
 */
public class CompositeShapeInputsTest {

    private static final double LOOSE = 0.05;

    @Test
    public void aSphereIsNeitherElongatedNorFlat() {
        LabelFeatureAccumulator.FeatureValues values = measure(ball(24, 9), null);
        assertEquals("a sphere's axes are equal, so elongation is 1",
                1.0, values.elongation(), LOOSE);
        assertEquals("and so is flatness", 1.0, values.flatness(), LOOSE);
    }

    /**
     * A solid ellipsoid is the shape spareness is defined against, so it must read 1.
     * Discretisation is the only reason it will not read exactly 1.
     */
    @Test
    public void aSolidEllipsoidHasSparenessOne() {
        LabelFeatureAccumulator.FeatureValues values = measure(ellipsoid(40, 14, 8, 5), null);
        assertEquals(1.0, values.spareness(), LOOSE);
    }

    /**
     * Two arms crossing at the centre fill only a small part of the ellipsoid that
     * fits them, which is the case spareness exists to detect.
     */
    @Test
    public void aSparseCrossHasSparenessWellBelowOne() {
        LabelFeatureAccumulator.FeatureValues values = measure(cross(32, 13), null);
        assertTrue("a cross should be far from solid, found " + values.spareness(),
                values.spareness() < 0.5);
        assertTrue("but still positive", values.spareness() > 0.0);
    }

    /**
     * A rod is elongated but not flat; a slab is flat but not elongated. If the two
     * ratios were the same quantity, one of these would fail.
     */
    @Test
    public void elongationAndFlatnessSeparateARodFromASlab() {
        LabelFeatureAccumulator.FeatureValues rod = measure(box(30, 2, 2, 20), null);
        LabelFeatureAccumulator.FeatureValues slab = measure(box(30, 16, 16, 2), null);
        assertTrue("a rod is elongated: " + rod.elongation(), rod.elongation() > 5.0);
        assertTrue("a rod is not flat: " + rod.flatness(), rod.flatness() < 1.5);
        assertTrue("a slab is flat: " + slab.flatness(), slab.flatness() > 5.0);
    }

    /**
     * Every surface voxel of a sphere sits at the same distance from its centre.
     *
     * <p>{@code ball(24, 9)} takes a <i>diameter</i>, so the expected mean is 4.5 and
     * not 9. The first version of this test asserted 9 and failed at 4.12, which was
     * the test misreading its own fixture rather than the measurement being wrong.
     */
    @Test
    public void surfaceDistancesOfASphereAreNearlyConstant() {
        LabelFeatureAccumulator.FeatureValues values = measure(ball(24, 9), null);
        assertEquals("the mean should be the radius", 4.5, values.surfaceDistanceMean(), 0.6);
        assertTrue("and the spread should be small: " + values.surfaceDistanceStdDev(),
                values.surfaceDistanceStdDev() < 1.0);
    }

    /** A cross has surface voxels at every distance from centre to arm tip. */
    @Test
    public void surfaceDistancesOfACrossAreSpreadOut() {
        LabelFeatureAccumulator.FeatureValues cross = measure(cross(32, 13), null);
        LabelFeatureAccumulator.FeatureValues ball = measure(ball(32, 13), null);
        assertTrue("a cross must spread more than a ball of the same reach: "
                        + cross.surfaceDistanceStdDev() + " vs " + ball.surfaceDistanceStdDev(),
                cross.surfaceDistanceStdDev() > ball.surfaceDistanceStdDev());
    }

    /** The distances are calibrated, so scaling the voxels scales the statistics. */
    @Test
    public void surfaceDistancesFollowTheCalibration() {
        LabelFeatureAccumulator.FeatureValues plain = measure(ball(24, 9), null);
        LabelFeatureAccumulator.FeatureValues scaled = measure(ball(24, 9), calibration(2.0, 2.0, 2.0));
        assertEquals(2.0 * plain.surfaceDistanceMean(), scaled.surfaceDistanceMean(), 1e-9);
        assertEquals(2.0 * plain.surfaceDistanceStdDev(), scaled.surfaceDistanceStdDev(), 1e-9);
    }

    /** A single voxel has no axes to compare and no spread to report. */
    @Test
    public void aSingleVoxelReportsNothingRatherThanZero() {
        ImagePlus image = blank(8, 8);
        image.getStack().getProcessor(4).set(4, 4, 1);
        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(image, null, null).valuesForLabel(1);
        assertTrue("flatness of one voxel is undefined", Double.isNaN(values.flatness()));
        assertTrue("spareness of one voxel is undefined", Double.isNaN(values.spareness()));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static LabelFeatureAccumulator.FeatureValues measure(ImagePlus labels,
                                                                 Calibration calibration) {
        return LabelFeatureAccumulator.scan(labels, null, calibration).valuesForLabel(1);
    }

    private static ImagePlus blank(int size, int depth) {
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < depth; z++) stack.addSlice(new ShortProcessor(size, size));
        return new ImagePlus("composite-inputs", stack);
    }

    private static ImagePlus ball(int size, int diameter) {
        return ellipsoid(size, diameter, diameter, diameter);
    }

    private static ImagePlus ellipsoid(int size, int spanX, int spanY, int spanZ) {
        int depth = Math.max(size, spanZ + 4);
        ImagePlus image = blank(size, depth);
        double cx = size / 2.0;
        double cy = size / 2.0;
        double cz = depth / 2.0;
        double rx = spanX / 2.0;
        double ry = spanY / 2.0;
        double rz = spanZ / 2.0;
        ImageStack stack = image.getStack();
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double nx = (x - cx) / rx;
                    double ny = (y - cy) / ry;
                    double nz = (z - cz) / rz;
                    if (nx * nx + ny * ny + nz * nz <= 1.0) stack.getProcessor(z + 1).set(x, y, 1);
                }
            }
        }
        return image;
    }

    private static ImagePlus box(int size, int spanX, int spanY, int spanZ) {
        int depth = Math.max(size, spanZ + 4);
        ImagePlus image = blank(size, depth);
        int x0 = (size - spanX) / 2;
        int y0 = (size - spanY) / 2;
        int z0 = (depth - spanZ) / 2;
        ImageStack stack = image.getStack();
        for (int z = z0; z < z0 + spanZ; z++) {
            for (int y = y0; y < y0 + spanY; y++) {
                for (int x = x0; x < x0 + spanX; x++) {
                    stack.getProcessor(z + 1).set(x, y, 1);
                }
            }
        }
        return image;
    }

    /** Three one-voxel-thick arms through the centre: reach without volume. */
    private static ImagePlus cross(int size, int arm) {
        ImagePlus image = blank(size, size);
        int centre = size / 2;
        ImageStack stack = image.getStack();
        for (int offset = -arm / 2; offset <= arm / 2; offset++) {
            int x = centre + offset;
            int y = centre + offset;
            int z = centre + offset;
            if (x >= 0 && x < size) stack.getProcessor(centre + 1).set(x, centre, 1);
            if (y >= 0 && y < size) stack.getProcessor(centre + 1).set(centre, y, 1);
            if (z >= 0 && z < size) stack.getProcessor(z + 1).set(centre, centre, 1);
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
}
