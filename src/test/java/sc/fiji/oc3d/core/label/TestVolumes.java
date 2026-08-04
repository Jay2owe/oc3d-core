package sc.fiji.oc3d.core.label;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.Random;

/** Fixture builders shared by the labelling tests. */
final class TestVolumes {

    private TestVolumes() {
        // Utility class.
    }

    /**
     * Builds an 8-bit volume from ASCII slices.
     *
     * <p>Each string is one row; each element of {@code slices} is one z plane.
     * {@code '.'} is background (0), anything else is foreground (255). Writing
     * fixtures this way keeps the geometry visible in the test, which matters
     * when the thing under test is a neighbourhood rule.
     */
    static ImagePlus fromAscii(String[][] slices) {
        int depth = slices.length;
        int height = slices[0].length;
        int width = slices[0][0].length();
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                String row = slices[z][y];
                if (row.length() != width) {
                    throw new IllegalArgumentException("ragged fixture at z=" + z + ", y=" + y
                            + " (length=" + row.length() + ", expected " + width + ").");
                }
                for (int x = 0; x < width; x++) {
                    if (row.charAt(x) != '.') processor.set(x, y, 255);
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("ascii", stack);
    }

    /** 8-bit volume from explicit values, indexed {@code [z][y][x]}. */
    static ImagePlus fromValues(int[][][] values) {
        int depth = values.length;
        int height = values[0].length;
        int width = values[0][0].length;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.set(x, y, values[z][y][x]);
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("values", stack);
    }

    /** Empty volume of the given size and bit depth (8, 16 or 32). */
    static ImagePlus blank(int width, int height, int depth, int bitDepth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(processor(width, height, bitDepth));
        }
        return new ImagePlus("blank", stack);
    }

    /**
     * Pseudo-random speckle at a given foreground fraction. Deterministic for a
     * given seed - a corpus that changes between runs cannot certify anything.
     */
    static ImagePlus speckle(int width, int height, int depth, double fraction, long seed) {
        Random random = new Random(seed);
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int i = 0; i < width * height; i++) {
                if (random.nextDouble() < fraction) processor.set(i, 255);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("speckle", stack);
    }

    /**
     * Pseudo-random blobs: speckled seeds grown by a box dilation, which
     * produces objects with genuine 3D extent rather than isolated voxels.
     */
    static ImagePlus blobs(int width, int height, int depth, int seedCount, int radius, long seed) {
        Random random = new Random(seed);
        byte[][] planes = new byte[depth][width * height];
        for (int s = 0; s < seedCount; s++) {
            int cx = random.nextInt(width);
            int cy = random.nextInt(height);
            int cz = random.nextInt(depth);
            int r = 1 + random.nextInt(Math.max(1, radius));
            for (int z = Math.max(0, cz - r); z <= Math.min(depth - 1, cz + r); z++) {
                for (int y = Math.max(0, cy - r); y <= Math.min(height - 1, cy + r); y++) {
                    for (int x = Math.max(0, cx - r); x <= Math.min(width - 1, cx + r); x++) {
                        planes[z][y * width + x] = (byte) 255;
                    }
                }
            }
        }
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ByteProcessor(width, height, planes[z], null));
        }
        return new ImagePlus("blobs", stack);
    }

    /** Reads a label image back as {@code [z][y][x]} ints. */
    static int[][][] labelsOf(ImagePlus labelImage) {
        ImageStack stack = labelImage.getStack();
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = stack.getSize();
        int[][][] out = new int[depth][height][width];
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[z][y][x] = LabelImages.labelFromPixel(processor.getf(y * width + x));
                }
            }
        }
        return out;
    }

    private static ImageProcessor processor(int width, int height, int bitDepth) {
        if (bitDepth == 8) return new ByteProcessor(width, height);
        if (bitDepth == 16) return new ShortProcessor(width, height);
        if (bitDepth == 32) return new FloatProcessor(width, height);
        throw new IllegalArgumentException("bitDepth must be 8, 16 or 32 (bitDepth="
                + bitDepth + ").");
    }
}
