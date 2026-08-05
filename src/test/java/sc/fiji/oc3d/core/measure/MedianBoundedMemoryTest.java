package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The median must not depend on how it was computed.
 *
 * <p>{@code Median} is the one column that is a selection rather than an
 * accumulation, so it cannot come from running sums. Retaining every value gives
 * an exact answer at O(voxels); for 8- and 16-bit intensity images a per-label
 * histogram gives the <em>same</em> exact answer in fixed space, because the bin
 * index is the sample value.
 *
 * <p>Two code paths producing one column is the arrangement that silently drifts,
 * so every case here computes the expected value by sorting the values
 * independently and compares. The sizes are chosen to sit either side of the
 * break-even point where the histogram takes over — a test using small objects
 * would exercise the retention path only and prove nothing about the other.
 */
public class MedianBoundedMemoryTest {

    /** Above 256 samples an 8-bit label switches to its histogram. */
    private static final int BYTE_BINS = 256;

    @Test
    public void eightBitAgreesWithASortedMedianBelowTheBreakEven() {
        int voxels = 101;
        float[] values = ramp(voxels, 251, 256);

        assertEquals(expectedMedian(values), medianOf(values, 8), 0.0);
    }

    @Test
    public void eightBitAgreesWithASortedMedianAboveTheBreakEven() {
        // Comfortably past 256, so the histogram is in use.
        int voxels = 1000;
        float[] values = ramp(voxels, 251, 256);

        assertEquals(expectedMedian(values), medianOf(values, 8), 0.0);
    }

    /**
     * The even-count branch averages the two middle samples, and it does so in
     * {@code float}. A histogram that returned the lower of the two, or averaged
     * in {@code double}, would pass an odd-count test and fail here.
     */
    @Test
    public void eightBitEvenCountAveragesTheTwoMiddleSamples() {
        int voxels = 1000;
        assertEquals("the sample count must be even for this test to mean anything",
                0, voxels % 2);
        float[] values = ramp(voxels, 251, 256);

        assertEquals(expectedMedian(values), medianOf(values, 8), 0.0);
    }

    @Test
    public void eightBitOddCountSelectsTheMiddleSample() {
        int voxels = 999;
        float[] values = ramp(voxels, 251, 256);

        assertEquals(expectedMedian(values), medianOf(values, 8), 0.0);
    }

    /** All one value: the degenerate case a histogram gets wrong most easily. */
    @Test
    public void aUniformLabelHasThatValueAsItsMedian() {
        float[] values = new float[BYTE_BINS * 4];
        Arrays.fill(values, 42f);

        assertEquals(42.0, medianOf(values, 8), 0.0);
    }

    @Test
    public void theExtremeBinsAreReachable() {
        float[] low = new float[BYTE_BINS * 2];
        Arrays.fill(low, 0f);
        assertEquals(0.0, medianOf(low, 8), 0.0);

        float[] high = new float[BYTE_BINS * 2];
        Arrays.fill(high, 255f);
        assertEquals(255.0, medianOf(high, 8), 0.0);
    }

    @Test
    public void sixteenBitAgreesWithASortedMedianAboveItsBreakEven() {
        // 16-bit switches at 65536 samples, so this has to be a large label.
        int voxels = 70000;
        float[] values = ramp(voxels, 40507, 65536);

        assertEquals(expectedMedian(values), medianOf(values, 16), 0.0);
    }

    @Test
    public void sixteenBitAgreesWithASortedMedianBelowItsBreakEven() {
        int voxels = 5000;
        float[] values = ramp(voxels, 40507, 65536);

        assertEquals(expectedMedian(values), medianOf(values, 16), 0.0);
    }

    /**
     * 32-bit intensities are arbitrary reals, so there is no histogram to fall
     * back on and the values are retained. The answer must still be the exact
     * median, including the fractional averaging of an even count.
     */
    @Test
    public void thirtyTwoBitRetainsValuesAndStillSelectsExactly() {
        float[] values = new float[400];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i * 0.25f) - 13.5f;
        }

        assertEquals(expectedMedian(values), medianOf(values, 32), 0.0);
    }

    // ------------------------------------------------------------------

    /**
     * A spread of values that is neither sorted in the image nor uniform, so a
     * histogram and a sorted array only agree if both are actually correct.
     * {@code stride} is coprime with {@code modulus} so the sequence walks the
     * whole range instead of cycling through a few values.
     *
     * <p>The multiply is in {@code long}. In {@code int} it overflows above
     * roughly 32 000 samples and produces negative values, which a
     * {@code ShortProcessor} then clamps to zero — so the array and the image
     * would disagree and the test would be measuring its own arithmetic rather
     * than the accumulator's.
     */
    private static float[] ramp(int count, int stride, int modulus) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = (float) (((long) i * stride) % modulus);
        }
        return values;
    }

    private static double expectedMedian(float[] values) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if ((n & 1) == 0) {
            int lower = n / 2 - 1;
            return (sorted[lower] + sorted[lower + 1]) / 2f;
        }
        return sorted[(n + 1) / 2 - 1];
    }

    /**
     * Measures one label whose voxels carry {@code values}, on an intensity image
     * of the given bit depth, and returns its {@code Median}.
     */
    private static double medianOf(float[] values, int bitDepth) {
        int width = 512;
        int height = (values.length + width - 1) / width;
        int slices = 2;

        ImageStack labels = new ImageStack(width, height);
        ImageStack intensity = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            labels.addSlice(new ShortProcessor(width, height));
            intensity.addSlice(processor(bitDepth, width, height));
        }

        // One label, laid out across the first slice; the second stays background
        // so the label is not simply "every voxel in the stack".
        ImageProcessor labelSlice = labels.getProcessor(1);
        ImageProcessor intensitySlice = intensity.getProcessor(1);
        for (int i = 0; i < values.length; i++) {
            int x = i % width;
            int y = i / width;
            labelSlice.setf(x, y, 1f);
            intensitySlice.setf(x, y, values[i]);
        }

        ImagePlus labelImage = new ImagePlus("labels", labels);
        labelImage.setDimensions(1, slices, 1);
        ImagePlus intensityImage = new ImagePlus("intensity", intensity);
        intensityImage.setDimensions(1, slices, 1);
        assertEquals("the fixture must have the bit depth under test",
                bitDepth, intensityImage.getBitDepth());

        LabelFeatureAccumulator.FeatureValues measured =
                LabelFeatureAccumulator.scan(labelImage, intensityImage, null).valuesForLabel(1);
        assertTrue("the fixture produced no object", measured != null);
        assertEquals("every voxel of the label must have been measured",
                values.length, measured.voxelCount());
        return measured.median();
    }

    private static ImageProcessor processor(int bitDepth, int width, int height) {
        if (bitDepth == 8) return new ByteProcessor(width, height);
        if (bitDepth == 16) return new ShortProcessor(width, height);
        return new FloatProcessor(width, height);
    }
}
