package sc.fiji.oc3d.core.ui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

/**
 * What a threshold dialog should show before the user touches anything.
 *
 * <p>Opening on the centre slice with an IsoData threshold is what the original
 * 3D Objects Counter does, and it matters more than it sounds: a stack's first
 * slice is usually its emptiest, so a dialog that opens there shows an empty
 * preview and an auto-threshold computed from noise.
 *
 * <p>No Swing here - these are numbers a dialog asks for, so they can be tested
 * headless.
 */
public final class DialogDefaults {

    private DialogDefaults() {}

    /** 1-based centre slice; 1 for a single-slice image. */
    public static int centerSlice(ImagePlus image) {
        int slices = image == null ? 1 : Math.max(1, image.getNSlices());
        return Math.max(1, (slices + 1) / 2);
    }

    /** Moves the displayed position to the centre slice, keeping channel and frame. */
    public static void moveToCenterSlice(ImagePlus image) {
        if (image == null) return;
        int channel = Math.max(1, image.getC());
        int frame = Math.max(1, image.getT());
        image.setPosition(channel, centerSlice(image), frame);
        image.updateAndDraw();
    }

    /**
     * IsoData auto-threshold of the centre slice.
     *
     * @param fallback returned when there is no processor, when the threshold
     *                 cannot be computed, or when it comes back saturated at an
     *                 int extreme - which means "no threshold found", not a
     *                 threshold of two billion
     */
    public static int isoDataThresholdAtCenterSlice(ImagePlus image, int fallback) {
        ImageProcessor processor = centerSliceProcessor(image);
        if (processor == null) return fallback;
        try {
            return clampToInt(processor.getAutoThreshold(), fallback);
        } catch (RuntimeException thresholdUnavailable) {
            return fallback;
        }
    }

    /** Lower end of a threshold slider: the smallest finite voxel in the stack. */
    public static int sliderMinimum(ImagePlus image) {
        double min = finiteRangeValue(image, 0.0, true);
        if (min >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (min <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.floor(min);
    }

    /**
     * Upper end of a threshold slider: the largest finite voxel, never below
     * {@code threshold} - a slider that cannot reach its own current value is
     * worse than one that is slightly too long.
     */
    public static int sliderMaximum(ImagePlus image, int threshold) {
        double max = finiteMaximum(image, Math.max(1, threshold));
        if (max >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (max <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        int rounded = (int) Math.ceil(max);
        return rounded < threshold ? threshold : rounded;
    }

    /** Largest finite voxel value across the whole stack. */
    public static double finiteMaximum(ImagePlus image, double fallback) {
        return finiteRangeValue(image, fallback, false);
    }

    /** Smallest finite voxel value across the whole stack. */
    public static double finiteMinimum(ImagePlus image, double fallback) {
        return finiteRangeValue(image, fallback, true);
    }

    /**
     * Scans every voxel, ignoring non-finite ones.
     *
     * <p>{@code ImageStatistics} would be faster, but a 32-bit stack holding
     * NaN or infinity - which real deconvolution output does - gives it a range
     * of NaN, and a slider built on that has no usable ends.
     */
    private static double finiteRangeValue(ImagePlus image, double fallback, boolean wantMinimum) {
        ImageStack stack = image == null ? null : image.getStack();
        if (stack == null || stack.size() <= 0) return fallback;
        double best = Double.NaN;
        for (int slice = 1; slice <= stack.size(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                float value = processor.getf(i);
                if (!Float.isFinite(value)) continue;
                if (Double.isNaN(best)
                        || (wantMinimum && value < best)
                        || (!wantMinimum && value > best)) {
                    best = value;
                }
            }
        }
        return Double.isNaN(best) ? fallback : best;
    }

    private static ImageProcessor centerSliceProcessor(ImagePlus image) {
        ImageStack stack = image == null ? null : image.getImageStack();
        if (stack == null || stack.size() <= 0) return null;
        int channel = clamp(Math.max(1, image.getC()), 1, Math.max(1, image.getNChannels()));
        int slice = centerSlice(image);
        int frame = clamp(Math.max(1, image.getT()), 1, Math.max(1, image.getNFrames()));
        int index = image.getStackIndex(channel, slice, frame);
        index = clamp(index, 1, stack.size());
        return stack.getProcessor(index);
    }

    private static int clampToInt(int value, int fallback) {
        if (value == Integer.MIN_VALUE || value == Integer.MAX_VALUE) return fallback;
        return value;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return value > max ? max : value;
    }
}
