package sc.fiji.oc3d.core.label;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Construction and validation helpers for label images.
 *
 * <p>Pure {@code ij.*}; no dialog, no {@code IJ.error}, no {@code System.exit}.
 * Everything here throws with a message naming the offending value, and the
 * calling plugin decides how to show it.
 */
public final class LabelImages {

    private LabelImages() {
        // Utility class.
    }

    /**
     * Smallest processor that can hold labels up to {@code maxLabel} without
     * wrapping.
     *
     * <p>8-bit to 255, 16-bit to 65,535, 32-bit float beyond. The float rung
     * matters: a fixed {@code ShortProcessor} silently wraps above 65,535
     * objects, turning object 65,536 into object 0 with no error - the exact
     * defect being fixed in CPC's {@code roiSetToLabelImage} on promotion.
     */
    public static ImageProcessor labelProcessor(int width, int height, long maxLabel) {
        requirePositiveDimensions(width, height);
        if (maxLabel <= 255L) return new ByteProcessor(width, height);
        if (maxLabel <= 65535L) return new ShortProcessor(width, height);
        return new FloatProcessor(width, height);
    }

    /**
     * Reads a label value from a processor index, applying the same rules the
     * measurement path uses: non-finite, negative and zero all mean background.
     */
    public static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        if (value > Integer.MAX_VALUE) return 0;
        return Math.round(value);
    }

    /**
     * Rejects inputs that cannot be labelled as a single z-series, naming the
     * reason.
     *
     * <p>Hyperstacks are rejected rather than flattened. An ImageJ stack holding
     * channels or frames alongside slices looks like a taller z-series to any
     * code that walks {@code ImageStack} linearly, so labelling it would join
     * objects across channels and multiply every volume. The caller extracts one
     * volume first - see {@link #volumeOf(ImagePlus, int, int)}.
     *
     * @param image image to check
     * @param role name used in the message, e.g. {@code "source"}
     */
    public static void requireLabellableVolume(ImagePlus image, String role) {
        String name = role == null || role.trim().isEmpty() ? "image" : role;
        if (image == null) {
            throw new IllegalArgumentException(
                    name + " must not be null (" + name + "=null; expected an ImagePlus).");
        }
        ImageStack stack = image.getStack();
        if (stack == null || stack.getSize() <= 0
                || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IllegalArgumentException(name + " must have a non-empty stack.");
        }
        if (image.getBitDepth() == 24) {
            throw new IllegalArgumentException(
                    name + " must not be an RGB image (bitDepth=24). Convert to 8-bit, "
                            + "16-bit or 32-bit, or split the channels, before labelling.");
        }
        int channels = Math.max(1, image.getNChannels());
        int frames = Math.max(1, image.getNFrames());
        if (channels > 1 || frames > 1) {
            throw new IllegalArgumentException(
                    name + " must be a single 3D volume, not a hyperstack (channels="
                            + channels + ", frames=" + frames + ", slices="
                            + Math.max(1, image.getNSlices())
                            + "). Extract one channel and frame first; treating the whole "
                            + "stack as a z-series would merge objects across channels and "
                            + "multiply every volume.");
        }
    }

    /**
     * Extracts one 3D volume from a hyperstack as an independent image.
     *
     * <p>Slices are copies, so the result can be labelled, filtered or discarded
     * without touching the source. Calibration is copied too.
     *
     * @param image source image; a plain stack is returned as a copy when
     *              {@code channel} and {@code frame} are both 1
     * @param channel 1-based channel
     * @param frame 1-based frame
     */
    public static ImagePlus volumeOf(ImagePlus image, int channel, int frame) {
        if (image == null) {
            throw new IllegalArgumentException(
                    "image must not be null (image=null; expected an ImagePlus).");
        }
        int channels = Math.max(1, image.getNChannels());
        int frames = Math.max(1, image.getNFrames());
        int slices = Math.max(1, image.getNSlices());
        if (channel < 1 || channel > channels) {
            throw new IllegalArgumentException("channel must be within 1.." + channels
                    + " (channel=" + channel + ").");
        }
        if (frame < 1 || frame > frames) {
            throw new IllegalArgumentException("frame must be within 1.." + frames
                    + " (frame=" + frame + ").");
        }

        ImageStack source = image.getStack();
        ImageStack out = new ImageStack(image.getWidth(), image.getHeight());
        for (int z = 1; z <= slices; z++) {
            int index = image.getStackIndex(channel, z, frame);
            ImageProcessor processor = source.getProcessor(index);
            out.addSlice(source.getSliceLabel(index),
                    processor == null ? null : processor.duplicate());
        }

        ImagePlus volume = new ImagePlus(image.getTitle(), out);
        Calibration calibration = image.getCalibration();
        if (calibration != null) {
            volume.setCalibration(calibration.copy());
        }
        return volume;
    }

    /**
     * Highest label present in a label image, as a long so that a 32-bit float
     * label image cannot silently overflow the check.
     */
    public static long maxLabel(ImagePlus labelImage) {
        requireLabellableVolume(labelImage, "labelImage");
        ImageStack stack = labelImage.getStack();
        int pixels = labelImage.getWidth() * labelImage.getHeight();
        long max = 0L;
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            if (processor == null) continue;
            for (int i = 0; i < pixels; i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label > max) max = label;
            }
        }
        return max;
    }

    private static void requirePositiveDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive (width="
                    + width + ", height=" + height + ").");
        }
    }
}
