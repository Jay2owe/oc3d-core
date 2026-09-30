package sc.fiji.oc3d.core.measure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.Random;

import org.junit.Test;

/**
 * The 0.2.1 {@link CentroidScan} (array lookups, the last label kept at hand,
 * raw pixel arrays of in-memory stacks) against the 0.2.0 scan kept as
 * {@link ReferenceCentroidScan}. Every centroid coordinate is compared as raw
 * bits, with and without intensity weights, on byte, short, float and RGB
 * label images, including float values that round, are negative, non-finite or
 * larger than any int.
 */
public class CentroidScanEquivalenceTest {

    @Test
    public void randomLabelImagesGiveIdenticalCentroids() {
        Random random = new Random(20260930L);
        long objects = 0;
        for (int trial = 0; trial < 400; trial++) {
            int width = 1 + random.nextInt(trial < 60 ? 5 : 70);
            int height = 1 + random.nextInt(trial < 60 ? 5 : 50);
            int depth = 1 + random.nextInt(trial < 60 ? 3 : 6);
            int type = random.nextInt(4);
            ImagePlus labels = labels(random, type, width, height, depth);
            ImagePlus intensity = trial % 3 == 0 ? null
                    : intensity(random, random.nextInt(3), width, height, depth);
            String expected = dump(ReferenceCentroidScan.scan(labels, intensity));
            String actual = dump(CentroidScan.scan(labels, intensity));
            assertEquals("trial " + trial, expected, actual);
            assertEquals(dump(ReferenceCentroidScan.scan(labels)), dump(CentroidScan.scan(labels)));
            CentroidScan.Result result = CentroidScan.scan(labels, intensity);
            for (CentroidScan.Centroid centroid : result.centroids()) {
                assertEquals(centroid, result.forLabel(centroid.label()));
            }
            objects += result.objectCount();
        }
        assertTrue("the images must hold objects", objects > 5000);
    }

    @Test
    public void edgeValuesGiveIdenticalCentroids() {
        float[] values = {0f, -0f, 0.49f, 0.5f, 1.5f, 2.5f, -3f, Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 65535f, 65535.4f,
            65535.5f, 65536f, 2.1474836E9f, 3.0e9f, 16777216f, 7f, 7f, 7f};
        FloatProcessor processor = new FloatProcessor(5, 4, values);
        ImagePlus labels = new ImagePlus("edge", processor);
        FloatProcessor weights = new FloatProcessor(5, 4);
        for (int i = 0; i < values.length; i++) weights.setf(i, i % 5 == 0 ? Float.NaN : i - 3);
        ImagePlus intensity = new ImagePlus("w", weights);
        assertEquals(dump(ReferenceCentroidScan.scan(labels, intensity)),
                dump(CentroidScan.scan(labels, intensity)));
        assertEquals(dump(ReferenceCentroidScan.scan(labels)), dump(CentroidScan.scan(labels)));

        // Empty, and one label everywhere.
        ImagePlus empty = new ImagePlus("empty", new ShortProcessor(4, 3));
        assertEquals(dump(ReferenceCentroidScan.scan(empty)), dump(CentroidScan.scan(empty)));
        ShortProcessor full = new ShortProcessor(4, 3);
        full.set(65535);
        ImagePlus all = new ImagePlus("full", full);
        assertEquals(dump(ReferenceCentroidScan.scan(all)), dump(CentroidScan.scan(all)));
    }

    private static String dump(ReferenceCentroidScan.Result result) {
        StringBuilder out = new StringBuilder();
        for (ReferenceCentroidScan.Centroid c : result.centroids()) {
            out.append(c.label()).append(' ')
                    .append(Long.toHexString(Double.doubleToRawLongBits(c.x()))).append(' ')
                    .append(Long.toHexString(Double.doubleToRawLongBits(c.y()))).append(' ')
                    .append(Long.toHexString(Double.doubleToRawLongBits(c.z()))).append(' ')
                    .append(c.voxelCount()).append('\n');
        }
        return out.toString();
    }

    private static String dump(CentroidScan.Result result) {
        StringBuilder out = new StringBuilder();
        for (CentroidScan.Centroid c : result.centroids()) {
            out.append(c.label()).append(' ')
                    .append(Long.toHexString(Double.doubleToRawLongBits(c.x()))).append(' ')
                    .append(Long.toHexString(Double.doubleToRawLongBits(c.y()))).append(' ')
                    .append(Long.toHexString(Double.doubleToRawLongBits(c.z()))).append(' ')
                    .append(c.voxelCount()).append('\n');
        }
        return out.toString();
    }

    /** type: 0 byte, 1 short, 2 float (with odd values), 3 RGB. */
    private static ImagePlus labels(Random random, int type, int width, int height, int depth) {
        int max = type == 0 ? 255 : type == 1 ? 65535 : type == 2 ? 3000000 : 200;
        boolean dense = random.nextBoolean();
        ImageStack stack = new ImageStack(width, height);
        int[] block = new int[width * height];
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = type == 0 ? new ByteProcessor(width, height)
                    : type == 1 ? new ShortProcessor(width, height)
                    : type == 2 ? new FloatProcessor(width, height)
                    : new ColorProcessor(width, height);
            // Rows of runs: a label held for a few voxels, background between.
            int label = 0;
            for (int i = 0; i < block.length; i++) {
                if (random.nextInt(5) == 0) {
                    label = random.nextInt(3) == 0 ? 0
                            : 1 + random.nextInt(dense ? Math.min(max, 9) : max);
                }
                block[i] = label;
            }
            for (int i = 0; i < block.length; i++) {
                if (type == 0) {
                    ((byte[]) processor.getPixels())[i] = (byte) block[i];
                } else if (type == 1) {
                    ((short[]) processor.getPixels())[i] = (short) block[i];
                } else if (type == 2) {
                    float value = block[i];
                    int odd = random.nextInt(40);
                    if (odd == 0) value += 0.4f;
                    if (odd == 1) value = -value;
                    if (odd == 2) value = Float.NaN;
                    if (odd == 3) value = 5.0e9f;
                    if (odd == 4) value += 0.5f;
                    ((float[]) processor.getPixels())[i] = value;
                } else {
                    ((int[]) processor.getPixels())[i] = block[i];
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("labels", stack);
    }

    /** type: 0 byte, 1 short, 2 float with some non-finite and negative weights. */
    private static ImagePlus intensity(Random random, int type, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = type == 0 ? new ByteProcessor(width, height)
                    : type == 1 ? new ShortProcessor(width, height)
                    : new FloatProcessor(width, height);
            for (int i = 0; i < width * height; i++) {
                float value = type == 2 ? (float) (random.nextGaussian() * 300.0 + 100.0)
                        : random.nextInt(type == 0 ? 256 : 65536);
                if (type == 2 && random.nextInt(50) == 0) value = Float.NaN;
                processor.setf(i, value);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("intensity", stack);
    }
}
