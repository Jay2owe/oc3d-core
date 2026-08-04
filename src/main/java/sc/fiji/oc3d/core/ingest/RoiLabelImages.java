package sc.fiji.oc3d.core.ingest;

import ij.ImagePlus;
import ij.gui.Roi;

import java.io.File;
import java.io.IOException;

/**
 * The validating front door to {@link LabelUtils}.
 *
 * <p>{@code LabelUtils} is permissive because it is also called from inside the
 * core, where the inputs are already known good. This class is what a plugin or
 * a macro calls: it rejects an empty ROI set, a missing path and a null
 * reference image up front, with a message naming what was wrong, instead of
 * returning an all-background label image that measures as "0 objects" and
 * leaves the user guessing.
 */
public final class RoiLabelImages {

    private RoiLabelImages() {
        // Utility class.
    }

    /** @see LabelUtils#loadRoiSet(String) */
    public static Roi[] loadRoiSet(String path) throws IOException {
        return LabelUtils.loadRoiSet(path);
    }

    /**
     * @param reference source of dimensions and calibration
     * @param rois      at least one ROI
     * @throws IllegalArgumentException if {@code rois} is null or empty
     */
    public static ImagePlus fromRois(ImagePlus reference, Roi[] rois) {
        validate(reference, rois);
        return LabelUtils.roiSetToLabelImage(reference, rois);
    }

    /**
     * Loads an ROI set and converts it in one step.
     *
     * <p>The result is titled after the ROI file rather than after the reference
     * image, because in batch the ROI file is what distinguishes one run from
     * the next.
     *
     * @throws IllegalArgumentException if the path is blank or the set is empty
     */
    public static ImagePlus fromRoiSetFile(ImagePlus reference, String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("ROI set path must not be blank (path='" + path + "').");
        }
        ImagePlus labels = fromRois(reference, loadRoiSet(path));
        labels.setTitle(baseNameWithoutExtension(path));
        return labels;
    }

    private static void validate(ImagePlus reference, Roi[] rois) {
        if (reference == null) {
            throw new IllegalArgumentException(
                    "reference must not be null (reference=null; expected an ImagePlus).");
        }
        if (rois == null) {
            throw new IllegalArgumentException("rois must not be null (rois=null).");
        }
        if (rois.length == 0) {
            throw new IllegalArgumentException(
                    "rois must not be empty (rois.length=0). An empty ROI set would produce "
                            + "an all-background label image and report zero objects.");
        }
    }

    private static String baseNameWithoutExtension(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
