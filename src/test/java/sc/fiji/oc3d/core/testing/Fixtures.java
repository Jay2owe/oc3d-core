package sc.fiji.oc3d.core.testing;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Fixture builders shared across the core's tests.
 *
 * <p>Public, unlike {@code label/TestVolumes}, because these are used from
 * several test packages.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** Empty stack of the given size and bit depth (8, 16 or 32). */
    public static ImagePlus blank(String title, int width, int height, int depth, int bitDepth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(processor(width, height, bitDepth));
        }
        return new ImagePlus(title, stack);
    }

    /** Stack from explicit values, indexed {@code [z][y][x]}. */
    public static ImagePlus fromValues(String title, int[][][] values, int bitDepth) {
        int depth = values.length;
        int height = values[0].length;
        int width = values[0][0].length;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = processor(width, height, bitDepth);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.setf(x, y, values[z][y][x]);
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    /**
     * A solid axis-aligned box of one label inside an otherwise empty stack.
     *
     * @param size cube side of the whole stack
     */
    public static ImagePlus cube(String title, int size, int x0, int y0, int z0, int side, int label) {
        ImagePlus image = blank(title, size, size, size, label <= 255 ? 8 : 16);
        ImageStack stack = image.getStack();
        for (int z = z0; z < z0 + side; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = y0; y < y0 + side; y++) {
                for (int x = x0; x < x0 + side; x++) {
                    processor.setf(x, y, label);
                }
            }
        }
        return image;
    }

    /** Applies isotropic or anisotropic calibration in place. */
    public static ImagePlus calibrate(ImagePlus image, double width, double height, double depth, String unit) {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = width;
        calibration.pixelHeight = height;
        calibration.pixelDepth = depth;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }

    /** A stack of the same size as {@code like}, filled with one constant value. */
    public static ImagePlus constant(String title, ImagePlus like, float value) {
        ImagePlus out = blank(title, like.getWidth(), like.getHeight(), like.getStackSize(), 32);
        ImageStack stack = out.getStack();
        for (int z = 1; z <= stack.size(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                processor.setf(i, value);
            }
        }
        return out;
    }

    private static ImageProcessor processor(int width, int height, int bitDepth) {
        if (bitDepth == 8) return new ByteProcessor(width, height);
        if (bitDepth == 16) return new ShortProcessor(width, height);
        if (bitDepth == 32) return new FloatProcessor(width, height);
        throw new IllegalArgumentException("bitDepth must be 8, 16 or 32 (bitDepth=" + bitDepth + ").");
    }
}
