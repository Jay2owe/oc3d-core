package sc.fiji.oc3d.core.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Thread-safe image duplication and thresholding.
 *
 * <p>{@link ij.plugin.Duplicator} is not safe to call from several worker
 * threads at once - it reaches into shared ImageJ state - and a batch runner
 * does exactly that. Everything here rebuilds the stack by duplicating one
 * {@code ImageProcessor} at a time, which is.
 *
 * <p>Every method returns a detached copy owning its own pixels and its own
 * calibration, so engine code can read it on a background thread without
 * sharing the live ImageJ window stack.
 */
public final class ImageOps {

    private ImageOps() {}

    /** Thread-safe duplicate of the full image, or {@code null} if {@code src} is null. */
    public static ImagePlus duplicateThreadSafe(ImagePlus src) {
        if (src == null) return null;
        return duplicateThreadSafe(src,
                1, Math.max(1, src.getNChannels()),
                1, Math.max(1, src.getNSlices()),
                1, Math.max(1, src.getNFrames()));
    }

    /**
     * Detached full-stack snapshot for background processing. The returned
     * image owns copied pixels and copied calibration/dimension metadata.
     */
    public static ImagePlus processingSnapshot(ImagePlus src) {
        return duplicateThreadSafe(src);
    }

    /**
     * Thread-safe threshold copy that keeps intensities. Finite voxel values at
     * or above {@code threshold} are retained; all others are zeroed.
     *
     * <p>The comparison is {@code value >= threshold}, matching
     * {@link sc.fiji.oc3d.core.label.LabelParameters#threshold()}. Non-finite
     * voxels are treated as background rather than passed through, so a NaN in a
     * 32-bit stack cannot join two objects.
     */
    public static ImagePlus thresholdRetainedIntensityCopy(ImagePlus src, int threshold) {
        ImagePlus dup = duplicateThreadSafe(src);
        if (dup == null || dup.getStack() == null) return dup;
        ImageStack stack = dup.getStack();
        for (int s = 1; s <= stack.size(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            if (ip == null) {
                throw new IllegalStateException("Threshold copy has no pixels at stack index "
                        + s + " for image '" + titleOf(src) + "'.");
            }
            applyRetainedThreshold(ip, threshold);
        }
        return dup;
    }

    /**
     * Thread-safe threshold copy for labelling. The returned stack is 8-bit:
     * foreground voxels are 255 and rejected voxels are 0.
     */
    public static ImagePlus thresholdBinaryMaskCopy(ImagePlus src, int threshold) {
        if (src == null) return null;
        int nC = Math.max(1, src.getNChannels());
        int nZ = Math.max(1, src.getNSlices());
        int nT = Math.max(1, src.getNFrames());
        ImageStack inStack = src.getImageStack();
        if (inStack == null || inStack.size() == 0) {
            return thresholdBinaryCurrentPlaneCopy(src, threshold);
        }

        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        for (int t = 1; t <= nT; t++) {
            for (int z = 1; z <= nZ; z++) {
                for (int c = 1; c <= nC; c++) {
                    int idx = src.getStackIndex(c, z, t);
                    ImageProcessor ip = processorAt(inStack, idx, src);
                    ByteProcessor mask = new ByteProcessor(src.getWidth(), src.getHeight());
                    applyBinaryThreshold(ip, mask, threshold);
                    out.addSlice(sliceLabelAt(inStack, idx), mask);
                }
            }
        }

        ImagePlus dup = new ImagePlus(src.getTitle(), out);
        dup.setDimensions(nC, nZ, nT);
        dup.setOpenAsHyperStack(src.isHyperStack());
        Calibration cal = src.getCalibration();
        if (cal != null) dup.setCalibration(cal.copy());
        return dup;
    }

    /**
     * Lightweight fallback for virtual stacks whose full-stack preview cannot
     * be duplicated safely. Returns a thresholded copy of the displayed plane.
     */
    public static ImagePlus thresholdRetainedCurrentPlaneCopy(ImagePlus src, int threshold) {
        if (src == null) return null;
        ImageProcessor processor = src.getProcessor();
        if (processor == null) {
            throw new IllegalStateException("Current plane has no pixels for image '"
                    + titleOf(src) + "'.");
        }
        ImageProcessor copy = processor.duplicate();
        if (copy == null) {
            copy = new FloatProcessor(Math.max(1, src.getWidth()), Math.max(1, src.getHeight()));
        }
        applyRetainedThreshold(copy, threshold);
        ImagePlus dup = new ImagePlus(src.getTitle(), copy);
        Calibration cal = src.getCalibration();
        if (cal != null) dup.setCalibration(cal.copy());
        return dup;
    }

    /**
     * Thread-safe duplicate of a sub-range. Mirrors
     * {@code Duplicator.run(imp, firstC, lastC, firstZ, lastZ, firstT, lastT)}
     * but duplicates per slice so it can run concurrently from several worker
     * threads. Returns {@code null} if {@code src} is null.
     */
    public static ImagePlus duplicateThreadSafe(ImagePlus src,
            int firstC, int lastC, int firstZ, int lastZ,
            int firstT, int lastT) {
        if (src == null) return null;
        // Read the dimensions through the accessors before indexing. They call
        // ImagePlus.verifyDimensions(); getStackIndex() does not, so on an
        // ImagePlus built straight from an ImageStack - which is what a headless
        // run and every test does - it would still see nSlices=1 and silently
        // clamp every request to slice 1.
        requireVerifiedDimensions(src);
        int nC = lastC - firstC + 1;
        int nZ = lastZ - firstZ + 1;
        int nT = lastT - firstT + 1;
        ImageStack inStack = src.getImageStack();
        if (inStack == null || inStack.size() == 0) {
            return duplicateCurrentPlane(src);
        }
        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        for (int t = firstT; t <= lastT; t++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int c = firstC; c <= lastC; c++) {
                    int idx = src.getStackIndex(c, z, t);
                    ImageProcessor ip = processorAt(inStack, idx, src);
                    ImageProcessor copy = ip.duplicate();
                    if (copy == null) {
                        throw new IllegalStateException("Could not duplicate stack index "
                                + idx + " for image '" + titleOf(src) + "'.");
                    }
                    out.addSlice(sliceLabelAt(inStack, idx), copy);
                }
            }
        }
        ImagePlus dup = new ImagePlus(src.getTitle(), out);
        dup.setDimensions(nC, nZ, nT);
        dup.setOpenAsHyperStack(src.isHyperStack());
        Calibration cal = src.getCalibration();
        if (cal != null) dup.setCalibration(cal.copy());
        return dup;
    }

    private static ImagePlus duplicateCurrentPlane(ImagePlus src) {
        ImageProcessor processor = src.getProcessor();
        if (processor == null) {
            throw new IllegalStateException("Current plane has no pixels for image '"
                    + titleOf(src) + "'.");
        }
        ImageProcessor copy = processor.duplicate();
        if (copy == null) {
            throw new IllegalStateException("Could not duplicate current plane for image '"
                    + titleOf(src) + "'.");
        }
        ImagePlus dup = new ImagePlus(src.getTitle(), copy);
        Calibration cal = src.getCalibration();
        if (cal != null) dup.setCalibration(cal.copy());
        return dup;
    }

    private static ImagePlus thresholdBinaryCurrentPlaneCopy(ImagePlus src, int threshold) {
        ImageProcessor processor = src.getProcessor();
        if (processor == null) {
            throw new IllegalStateException("Current plane has no pixels for image '"
                    + titleOf(src) + "'.");
        }
        ByteProcessor mask = new ByteProcessor(src.getWidth(), src.getHeight());
        applyBinaryThreshold(processor, mask, threshold);
        ImagePlus dup = new ImagePlus(src.getTitle(), mask);
        Calibration cal = src.getCalibration();
        if (cal != null) dup.setCalibration(cal.copy());
        return dup;
    }

    private static ImageProcessor processorAt(ImageStack stack, int index, ImagePlus source) {
        if (stack == null) {
            throw new IllegalStateException("Image '" + titleOf(source) + "' has no stack.");
        }
        if (index < 1 || index > stack.size()) {
            throw new IllegalStateException("Stack index " + index + " is outside 1.."
                    + stack.size() + " for image '" + titleOf(source) + "'.");
        }
        ImageProcessor processor = stack.getProcessor(index);
        if (processor == null) {
            throw new IllegalStateException("Image stack returned no pixels at stack index "
                    + index + " for image '" + titleOf(source) + "'.");
        }
        return processor;
    }

    private static String sliceLabelAt(ImageStack stack, int index) {
        try {
            return stack.getSliceLabel(index);
        } catch (RuntimeException labelUnavailable) {
            return null;
        }
    }

    private static void applyRetainedThreshold(ImageProcessor ip, int threshold) {
        for (int i = 0; i < ip.getPixelCount(); i++) {
            float value = ip.getf(i);
            if (value < threshold || !Float.isFinite(value)) {
                ip.setf(i, 0f);
            }
        }
    }

    private static void applyBinaryThreshold(ImageProcessor src, ByteProcessor dst, int threshold) {
        int sharedPixels = Math.min(src.getPixelCount(), dst.getPixelCount());
        for (int i = 0; i < sharedPixels; i++) {
            float value = src.getf(i);
            if (value >= threshold && Float.isFinite(value)) {
                dst.set(i, 255);
            }
        }
    }

    /**
     * Forces {@code ImagePlus.verifyDimensions()} by reading through the
     * accessors, so a later {@code getStackIndex} sees the real dimensions.
     */
    private static void requireVerifiedDimensions(ImagePlus image) {
        image.getNChannels();
        image.getNSlices();
        image.getNFrames();
    }

    private static String titleOf(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }
}
