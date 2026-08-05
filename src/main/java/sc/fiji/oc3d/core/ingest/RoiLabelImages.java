package sc.fiji.oc3d.core.ingest;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
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
     * @param rois      at least one ROI, each enclosing an area inside
     *                  {@code reference}
     * @throws IllegalArgumentException if the set is null or empty, or any ROI
     *         would be silently dropped or silently mismeasured
     */
    public static ImagePlus fromRois(ImagePlus reference, Roi[] rois) {
        validate(reference, rois);
        checkEachRoiCanBeMeasured(reference, rois);
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

    /**
     * The rules that stop an ROI set being measured wrongly rather than
     * refused.
     *
     * <p>Promoted from Volumetric Colocalization, which had them and CPC did
     * not. Each one guards a failure that is invisible in the output: a
     * dropped ROI does not appear as an error, it appears as one fewer object
     * and a shifted denominator in every summary percentage.
     */
    private static void checkEachRoiCanBeMeasured(ImagePlus reference, Roi[] rois) {
        int width = reference.getWidth();
        int height = reference.getHeight();
        int slices = Math.max(1, reference.getNSlices());
        for (int i = 0; i < rois.length; i++) {
            Roi roi = rois[i];
            int label = i + 1;
            if (roi == null) {
                throw new IllegalArgumentException(describe(null, label)
                        + " is null. It would leave a gap in the label "
                        + "numbering and one fewer object than ROIs.");
            }
            // isArea() rather than isLine(): it also excludes point and
            // multipoint selections, which isLine() lets through. A line's
            // mask is null (straight lines) or the filled polygon of its
            // vertices (polylines), so filling one would invent a solid block
            // spanning its bounding box; a point marker would become a stray
            // one-voxel object competing for overlap.
            if (!roi.isArea()) {
                throw new IllegalArgumentException(describe(roi, label)
                        + " is a line, polyline, angle or point selection. "
                        + "Objects are measured by volume, so every ROI must "
                        + "enclose an area. Convert traced lines to areas "
                        + "first (Edit > Selection > Line to Area).");
            }
            if (roi.getBounds().isEmpty()) {
                // Caught before the bounds test below, which reports empty
                // rectangles as lying outside and sends the user hunting for
                // the wrong problem.
                throw new IllegalArgumentException(
                        describe(roi, label) + " encloses no pixels.");
            }
            if (!roi.getBounds().intersects(0, 0, width, height)) {
                throw new IllegalArgumentException(describe(roi, label)
                        + " lies entirely outside the reference image ("
                        + width + "x" + height + "). Use the reference image "
                        + "the ROIs were drawn on.");
            }
            if (pixelsInsideImage(roi, width, height) == 0) {
                // Two ways to get here: an empty mask despite ordinary bounds
                // (a polygon with collinear vertices is the usual one), or a
                // box that overlaps the image while the shape inside it does
                // not.
                //
                // Counted from the ROI itself rather than from the finished
                // label image, and that distinction matters: overlapping ROIs
                // resolve to the later label by design, so an ROI completely
                // covered by a later one contributes no pixels to the OUTPUT
                // while still enclosing plenty. Checking the output would
                // refuse that, which is documented, intended behaviour.
                throw new IllegalArgumentException(describe(roi, label)
                        + " encloses no pixels inside the reference image ("
                        + width + "x" + height + ").");
            }
            if (roi.getZPosition() > slices) {
                // The conversion draws a positioned ROI on its slice and an
                // unpositioned one on every slice. An ROI positioned beyond
                // the stack falls into the second branch, so it would be
                // smeared through the whole volume and measure many times its
                // real size. Refuse rather than mismeasure.
                throw new IllegalArgumentException(describe(roi, label)
                        + " is positioned on slice " + roi.getZPosition()
                        + " but the reference image has only " + slices
                        + " slice(s). Use the reference image the ROIs were "
                        + "drawn on.");
            }
        }
    }

    /**
     * How many pixels this ROI would label on one slice, clipped to the image.
     * Mirrors the fill loop's own condition, so the two cannot disagree.
     */
    private static int pixelsInsideImage(Roi roi, int width, int height) {
        Rectangle bounds = roi.getBounds();
        ImageProcessor mask = roi.getMask();
        int inside = 0;
        for (int y = 0; y < bounds.height; y++) {
            for (int x = 0; x < bounds.width; x++) {
                if (mask != null && mask.getPixel(x, y) <= 0) continue;
                int gx = bounds.x + x;
                int gy = bounds.y + y;
                if (gx >= 0 && gx < width && gy >= 0 && gy < height) inside++;
            }
        }
        return inside;
    }

    /** {@code ROI 12 ("cell_17")}, or just {@code ROI 12} when unnamed. */
    private static String describe(Roi roi, int label) {
        String name = roi == null ? null : roi.getName();
        return name == null || name.trim().isEmpty()
                ? "ROI " + label
                : "ROI " + label + " (\"" + name.trim() + "\")";
    }

    private static String baseNameWithoutExtension(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
