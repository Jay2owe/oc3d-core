package sc.fiji.oc3d.core.measure;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Feret direction set, held to the bound its javadoc claims.
 *
 * <p>The claim is not decorative. The estimate is a maximum of directional extents, so
 * for an object whose exact Feret pair vector is {@code v}, the extent along any
 * direction {@code d} is at least {@code |v| cos angle(v, d)}. The worst case over all
 * objects is therefore {@code 1 - cos(covering radius)} of the set, where the covering
 * radius is the largest angle any direction can be from its nearest member.
 *
 * <p>Before 2026-08-06 the set was the 13 lattice directions of the 26-neighbourhood,
 * whose covering radius is 27.56 degrees - a worst case of 11.35%. That was documented
 * as "about 6%", a figure taken from rods along small-integer lattice directions, which
 * sit unusually close to the sampled set and are therefore the best case rather than the
 * worst. Measured against mcib3d's exact pairwise Feret over 61 568 real objects, the
 * estimate reached 11.15%. This test exists so the number in the javadoc is recomputed
 * rather than believed.
 */
public class FeretDirectionsTest {

    /**
     * The set's covering radius is 15.0525 degrees, found by a 3000-step sweep with
     * local refinement to convergence. Declared with a margin above that so a coarser
     * sweep cannot land above the bound by accident.
     *
     * <p>The first version of this test declared 15.02, taken from a 900-step sweep
     * without refinement, and the 720-step sweep here found 15.0345 and failed it. The
     * bound was not loosened to accommodate a measurement: the maximum was computed
     * properly, which is a different act, and the number it produced is what is
     * declared here. Nothing about the direction set changed.
     */
    private static final double DECLARED_COVERING_RADIUS_DEGREES = 15.10;

    /** {@code 1 - cos(15.10 degrees)}, the worst under-estimate the set permits. */
    private static final double DECLARED_WORST_UNDER_ESTIMATE = 0.0345;

    private static final double UNIT_TOLERANCE = 1e-12;

    @Test
    public void holdsTheDeclaredNumberOfDirections() {
        assertEquals("FERET_DIRECTION_COUNT must match the generated set",
                LabelFeatureAccumulator.FERET_DIRECTION_COUNT,
                LabelFeatureAccumulator.FERET_DIRECTIONS.length);
    }

    @Test
    public void everyDirectionIsAUnitVector() {
        for (int i = 0; i < LabelFeatureAccumulator.FERET_DIRECTIONS.length; i++) {
            double[] d = LabelFeatureAccumulator.FERET_DIRECTIONS[i];
            double norm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            assertEquals("direction " + i + " must be a unit vector", 1.0, norm, UNIT_TOLERANCE);
        }
    }

    @Test
    public void noTwoDirectionsAreTheSameLine() {
        double[][] directions = LabelFeatureAccumulator.FERET_DIRECTIONS;
        for (int i = 0; i < directions.length; i++) {
            for (int j = i + 1; j < directions.length; j++) {
                double dot = Math.abs(directions[i][0] * directions[j][0]
                        + directions[i][1] * directions[j][1]
                        + directions[i][2] * directions[j][2]);
                assertTrue("directions " + i + " and " + j + " are the same line (|dot|=" + dot + ")",
                        dot < 1.0 - 1e-9);
            }
        }
    }

    /**
     * Every one of the original 13 must still be sampled. A maximum over a superset of
     * directions cannot be smaller, so keeping them is what guarantees no object's
     * Feret decreases - and that an axis-aligned or diagonal object, which is most
     * test material, keeps exactly the value it had.
     */
    @Test
    public void theThirteenLatticeDirectionsAreAllStillPresent() {
        double inverseRootTwo = 1.0 / Math.sqrt(2.0);
        double inverseRootThree = 1.0 / Math.sqrt(3.0);
        double[][] lattice = {
                {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
                {inverseRootTwo, inverseRootTwo, 0}, {inverseRootTwo, -inverseRootTwo, 0},
                {inverseRootTwo, 0, inverseRootTwo}, {inverseRootTwo, 0, -inverseRootTwo},
                {0, inverseRootTwo, inverseRootTwo}, {0, inverseRootTwo, -inverseRootTwo},
                {inverseRootThree, inverseRootThree, inverseRootThree},
                {inverseRootThree, inverseRootThree, -inverseRootThree},
                {inverseRootThree, -inverseRootThree, inverseRootThree},
                {-inverseRootThree, inverseRootThree, inverseRootThree}
        };
        for (int i = 0; i < lattice.length; i++) {
            assertTrue("lattice direction " + i + " is no longer sampled; removing it would let "
                            + "an object's Feret decrease",
                    isSampled(lattice[i]));
        }
    }

    /**
     * The bound itself, by dense sweep of the hemisphere. Directions are lines, so the
     * angle is taken to the nearer end of each sampled axis.
     */
    @Test
    public void theCoveringRadiusIsInsideTheDeclaredBound() {
        double worstGap = 0.0;
        double[] worstDirection = null;
        int steps = 720;
        for (int i = 0; i <= steps; i++) {
            double theta = Math.PI * i / steps;
            int phiSteps = Math.max(1, (int) Math.round(steps * Math.sin(theta) * 2));
            for (int j = 0; j < phiSteps; j++) {
                double phi = 2.0 * Math.PI * j / phiSteps;
                double[] probe = {
                        Math.sin(theta) * Math.cos(phi),
                        Math.sin(theta) * Math.sin(phi),
                        Math.cos(theta)};
                double gap = Math.acos(Math.min(1.0, nearestCosine(probe)));
                if (gap > worstGap) {
                    worstGap = gap;
                    worstDirection = probe;
                }
            }
        }
        double degrees = Math.toDegrees(worstGap);
        double worstUnderEstimate = 1.0 - Math.cos(worstGap);
        assertTrue("covering radius " + degrees + " deg exceeds the declared "
                        + DECLARED_COVERING_RADIUS_DEGREES + " deg (worst direction "
                        + describe(worstDirection) + ")",
                degrees <= DECLARED_COVERING_RADIUS_DEGREES);
        assertTrue("worst under-estimate " + worstUnderEstimate + " exceeds the declared "
                        + DECLARED_WORST_UNDER_ESTIMATE,
                worstUnderEstimate <= DECLARED_WORST_UNDER_ESTIMATE);
    }

    /**
     * The 13 alone are worse than the bound this set declares, by a factor of three.
     * Kept as a test rather than a comment so the reason for 64 directions cannot be
     * quietly reverted.
     */
    @Test
    public void theThirteenAloneWouldNotMeetTheBound() {
        double inverseRootTwo = 1.0 / Math.sqrt(2.0);
        double inverseRootThree = 1.0 / Math.sqrt(3.0);
        double[][] lattice = {
                {1, 0, 0}, {0, 1, 0}, {0, 0, 1},
                {inverseRootTwo, inverseRootTwo, 0}, {inverseRootTwo, -inverseRootTwo, 0},
                {inverseRootTwo, 0, inverseRootTwo}, {inverseRootTwo, 0, -inverseRootTwo},
                {0, inverseRootTwo, inverseRootTwo}, {0, inverseRootTwo, -inverseRootTwo},
                {inverseRootThree, inverseRootThree, inverseRootThree},
                {inverseRootThree, inverseRootThree, -inverseRootThree},
                {inverseRootThree, -inverseRootThree, inverseRootThree},
                {-inverseRootThree, inverseRootThree, inverseRootThree}
        };
        // The direction the sweep found worst for the 13: (0.367, 0.887, 0.282).
        double[] worst = {0.367, 0.887, 0.282};
        double norm = Math.sqrt(worst[0] * worst[0] + worst[1] * worst[1] + worst[2] * worst[2]);
        double[] unit = {worst[0] / norm, worst[1] / norm, worst[2] / norm};
        double best = 0.0;
        for (int i = 0; i < lattice.length; i++) {
            double[] d = lattice[i];
            double dot = Math.abs(unit[0] * d[0] + unit[1] * d[1] + unit[2] * d[2]);
            if (dot > best) best = dot;
        }
        double thirteenGap = Math.toDegrees(Math.acos(Math.min(1.0, best)));
        assertTrue("the 13 lattice directions should leave a gap above 27 deg here, found "
                        + thirteenGap, thirteenGap > 27.0);
        assertTrue("the full set must do much better on that same direction",
                Math.toDegrees(Math.acos(Math.min(1.0, nearestCosine(unit))))
                        < DECLARED_COVERING_RADIUS_DEGREES);
    }

    private static boolean isSampled(double[] direction) {
        double norm = Math.sqrt(direction[0] * direction[0]
                + direction[1] * direction[1] + direction[2] * direction[2]);
        double[] unit = {direction[0] / norm, direction[1] / norm, direction[2] / norm};
        return nearestCosine(unit) > 1.0 - 1e-12;
    }

    private static double nearestCosine(double[] unit) {
        double best = 0.0;
        for (int i = 0; i < LabelFeatureAccumulator.FERET_DIRECTIONS.length; i++) {
            double[] d = LabelFeatureAccumulator.FERET_DIRECTIONS[i];
            double dot = Math.abs(unit[0] * d[0] + unit[1] * d[1] + unit[2] * d[2]);
            if (dot > best) best = dot;
        }
        return best;
    }

    private static String describe(double[] direction) {
        if (direction == null) return "none";
        return String.format(java.util.Locale.ROOT, "(%+.3f,%+.3f,%+.3f)",
                direction[0], direction[1], direction[2]);
    }
}
