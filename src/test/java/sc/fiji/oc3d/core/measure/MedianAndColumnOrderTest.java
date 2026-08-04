package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.testing.Fixtures;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The {@code Median} column and the canonical column order.
 *
 * <p>Both were introduced so that one measurement engine can serve every input
 * shape without the classic {@code Counter3D} output moving. Neither was pinned by
 * a test before.
 *
 * <p>The order matters even though the migration's harness compares column
 * <i>sets</i> and only checks order when the sets are the same size: the order is
 * what a user sees in the Results window and in {@code batch_objects.csv}, and it
 * was chosen to be the classic path's so that Case A output is unchanged. A
 * well-meaning tidy-up that reordered it would move Case A without any diff
 * necessarily catching it, which is exactly why it is asserted here.
 */
public class MedianAndColumnOrderTest {

    /** The order the classic path emits, which is now the order every path emits. */
    private static final String[] EXPECTED_ORDER = {
            "Volume (pixel^3)",
            "Surface (pixel^2)",
            "Nb of obj. voxels",
            "Nb of surf. voxels",
            "IntDen",
            "Mean",
            "StdDev",
            "Median",
            "Min",
            "Max",
            "X",
            "Y",
            "Z",
            "XM",
            "YM",
            "ZM",
            "BX",
            "BY",
            "BZ",
            "B-width",
            "B-height",
            "B-depth",
            "Label",
            "Morph_Sphericity",
            "Morph_Compactness",
            "Morph_Elongation",
            "Morph_Feret3D_um"
    };

    @Test
    public void columnOrderMatchesTheClassicPathExactly() {
        ImagePlus labels = Fixtures.cube("labels", 8, 2, 2, 2, 3, 1);
        ImagePlus intensity = Fixtures.constant("intensity", labels, 50f);
        try {
            ResultsTable table = LabelFeatureAccumulator.scan(labels, intensity, null)
                    .toStatisticsTable();
            assertEquals("column order changed; the harness compares it as an exact string",
                    Arrays.asList(EXPECTED_ORDER), Arrays.asList(table.getHeadings()));
        } finally {
            discard(labels);
            discard(intensity);
        }
    }

    @Test
    public void emptyTableCarriesTheSameOrder() {
        ResultsTable table = LabelFeatureAccumulator.emptyStatisticsTable(null);
        assertEquals(Arrays.asList(EXPECTED_ORDER), Arrays.asList(table.getHeadings()));
    }

    /**
     * An odd voxel count selects the middle value.
     *
     * <p>{@code Utilities.Object3D.median} computes the index as
     * {@code (n + 1) / 2 - 1}, which for odd {@code n} is the true middle.
     */
    @Test
    public void medianOfAnOddCountIsTheMiddleValue() {
        // Five voxels, intensities 10 20 30 40 50 -> 30.
        assertEquals(30.0, medianOfLine(new float[] {10f, 20f, 30f, 40f, 50f}), 0.0);
    }

    /**
     * An even voxel count averages the two middle values <b>in {@code float}</b>.
     *
     * <p>This is the case where a plausible alternative — taking the lower of the
     * two — would silently disagree with the shipped plugin on half of all
     * objects, so it is asserted rather than assumed.
     */
    @Test
    public void medianOfAnEvenCountAveragesTheTwoMiddleValues() {
        // Four voxels, 10 20 30 40 -> (20 + 30) / 2 = 25.
        assertEquals(25.0, medianOfLine(new float[] {10f, 20f, 30f, 40f}), 0.0);
    }

    /** Order of appearance must not matter: the values are sorted first. */
    @Test
    public void medianIsIndependentOfScanOrder() {
        double ascending = medianOfLine(new float[] {1f, 2f, 3f, 100f, 200f});
        double shuffled = medianOfLine(new float[] {200f, 3f, 1f, 100f, 2f});
        assertEquals(ascending, shuffled, 0.0);
    }

    /**
     * The even-count average is a {@code float} division, matching the reference's
     * own type. 1 and 2 average to 1.5, which is exact; 1 and 4 average to 2.5.
     * The case that matters is a pair whose mean is not representable in binary,
     * where a {@code double} division would produce a different last bit.
     */
    @Test
    public void evenCountAverageIsComputedInFloat() {
        float a = 16777217f; // 2^24 + 1, not representable: stored as 16777216
        float b = 3f;
        double expected = (a + b) / 2f;
        assertEquals(expected, medianOfLine(new float[] {a, b}), 0.0);
    }

    @Test
    public void medianIsNaNWithoutAnIntensityImage() {
        ImagePlus labels = Fixtures.cube("labels", 8, 2, 2, 2, 3, 1);
        try {
            LabelFeatureAccumulator.Result result =
                    LabelFeatureAccumulator.scan(labels, null, null);
            assertTrue("no intensity image means no median",
                    Double.isNaN(result.valuesForLabel(1).median()));
        } finally {
            discard(labels);
        }
    }

    @Test
    public void medianSurvivesAnObjectLargerThanTheInitialBuffer() {
        // The retention buffer starts at 16 entries and grows geometrically; a
        // 125-voxel object exercises several growth steps.
        ImagePlus labels = Fixtures.cube("labels", 12, 2, 2, 2, 5, 1);
        ImagePlus intensity = Fixtures.blank("intensity", 12, 12, 12, 16);
        try {
            // Distinct values so a lost or duplicated entry moves the median.
            int written = 0;
            for (int z = 0; z < 12; z++) {
                ImageProcessor labelSlice = labels.getStack().getProcessor(z + 1);
                ImageProcessor intensitySlice = intensity.getStack().getProcessor(z + 1);
                for (int i = 0; i < 12 * 12; i++) {
                    if (labelSlice.getf(i) > 0f) {
                        intensitySlice.setf(i, ++written);
                    }
                }
            }
            assertEquals("fixture should be a 5x5x5 cube", 125, written);
            LabelFeatureAccumulator.Result result =
                    LabelFeatureAccumulator.scan(labels, intensity, null);
            // 1..125 -> median 63.
            assertEquals(63.0, result.valuesForLabel(1).median(), 0.0);
            assertEquals(125L, result.valuesForLabel(1).voxelCount());
        } finally {
            discard(labels);
            discard(intensity);
        }
    }

    /**
     * Measures a single object whose voxels carry the given intensities, laid out
     * along a line so they form one 26-connected component.
     */
    private static double medianOfLine(float[] intensities) {
        int length = intensities.length;
        ImagePlus labels = Fixtures.blank("labels", length + 2, 3, 1, 16);
        ImagePlus intensity = Fixtures.blank("intensity", length + 2, 3, 1, 32);
        try {
            ImageProcessor labelSlice = labels.getStack().getProcessor(1);
            ImageProcessor intensitySlice = intensity.getStack().getProcessor(1);
            int width = length + 2;
            for (int i = 0; i < length; i++) {
                int index = width * 1 + (1 + i);   // row y=1, columns 1..length
                labelSlice.setf(index, 1f);
                intensitySlice.setf(index, intensities[i]);
            }
            LabelFeatureAccumulator.Result result =
                    LabelFeatureAccumulator.scan(labels, intensity, null);
            LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);
            assertEquals("fixture wrote the wrong number of voxels",
                    (long) length, values.voxelCount());
            return values.median();
        } finally {
            discard(labels);
            discard(intensity);
        }
    }

    private static void discard(ImagePlus image) {
        if (image != null) image.flush();
    }
}
