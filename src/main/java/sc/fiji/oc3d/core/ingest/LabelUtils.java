package sc.fiji.oc3d.core.ingest;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import sc.fiji.oc3d.core.label.LabelImages;

/**
 * Turns ImageJ ROIs into label images.
 *
 * <p>This is the entry point for the "I already have my segmentation" case:
 * ROIs drawn by hand, exported from the ROI Manager, or produced by another
 * plugin. Lifted from CPC, where it was the only way to get an external
 * segmentation into the pipeline, and put here so every variant gets it.
 *
 * <p><b>One behaviour change on promotion.</b> The original always allocated a
 * 16-bit label image, which silently wrapped above 65,535 ROIs - ROI 65,536
 * became label 0, i.e. background, with no error. Bit depth now follows the ROI
 * count via {@link LabelImages#labelProcessor}, so a large ROI set is labelled
 * correctly instead of quietly losing objects.
 */
public final class LabelUtils {

    private LabelUtils() {
        // Utility class.
    }

    /**
     * Loads ROIs from a {@code .zip} ROI set or a single {@code .roi} file.
     *
     * @param path file to read
     * @return the ROIs in the order the archive stores them; empty, never null
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if {@code path} is null or blank
     */
    public static Roi[] loadRoiSet(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "ROI set path must not be blank (path='" + path + "').");
        }
        if (path.toLowerCase(Locale.ROOT).endsWith(".roi")) {
            Roi roi = new RoiDecoder(path).getRoi();
            return roi != null ? new Roi[]{roi} : new Roi[0];
        }

        List<Roi> rois = new ArrayList<Roi>();
        byte[] buf = new byte[65536];
        InputStream in = new FileInputStream(path);
        ZipInputStream zis = new ZipInputStream(in);
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Case-insensitively, and under Locale.ROOT: a set containing
                // CELL.ROI was silently skipped, and a Turkish default locale
                // lowercases 'I' to a dotless one that never matches ".roi".
                if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".roi")) continue;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) baos.write(buf, 0, n);
                Roi roi = new RoiDecoder(baos.toByteArray(), entry.getName()).getRoi();
                if (roi != null) rois.add(roi);
            }
        } finally {
            zis.close();
        }
        return rois.toArray(new Roi[0]);
    }

    /**
     * Converts ROIs into a label image sized and calibrated like
     * {@code reference}.
     *
     * <p>Each ROI gets its index plus one as its label, so ROI order in the file
     * is the label order in the output - a user who renames "object 3" in the
     * ROI Manager still finds it as label 3.
     *
     * <p>An ROI carrying a z-position is drawn on that slice only. One without
     * is drawn on <em>every</em> slice, which is what makes a 2D ROI usable as a
     * column through the stack. Later ROIs overwrite earlier ones where they
     * overlap; a label image cannot represent a voxel belonging to two objects.
     *
     * @param reference source of width, height, slice count and calibration
     * @param rois      the ROIs to convert
     * @return label image with values {@code 0} and {@code 1..rois.length}
     * @throws IllegalArgumentException if either argument is null
     */
    public static ImagePlus roiSetToLabelImage(ImagePlus reference, Roi[] rois) {
        if (reference == null) {
            throw new IllegalArgumentException(
                    "reference must not be null (reference=null; expected an ImagePlus).");
        }
        if (rois == null) {
            throw new IllegalArgumentException("rois must not be null (rois=null).");
        }
        int w = reference.getWidth();
        int h = reference.getHeight();
        int nSlices = Math.max(1, reference.getNSlices());

        ImageStack stack = new ImageStack(w, h);
        for (int z = 0; z < nSlices; z++) {
            stack.addSlice(LabelImages.labelProcessor(w, h, rois.length));
        }

        for (int i = 0; i < rois.length; i++) {
            Roi roi = rois[i];
            if (roi == null) continue;
            int label = i + 1;
            int zPos = roi.getZPosition();

            if (zPos > 0 && zPos <= nSlices) {
                fillRoi(stack.getProcessor(zPos), roi, label);
            } else {
                for (int z = 1; z <= nSlices; z++) {
                    fillRoi(stack.getProcessor(z), roi, label);
                }
            }
        }

        ImagePlus result = new ImagePlus("Labels from ROIs", stack);
        if (reference.getCalibration() != null) {
            result.setCalibration(reference.getCalibration().copy());
        }
        result.setDisplayRange(0, Math.max(1, rois.length));
        return result;
    }

    private static void fillRoi(ImageProcessor ip, Roi roi, int label) {
        Rectangle bounds = roi.getBounds();
        ImageProcessor mask = roi.getMask();

        for (int y = 0; y < bounds.height; y++) {
            for (int x = 0; x < bounds.width; x++) {
                if (mask == null || mask.getPixel(x, y) > 0) {
                    int gx = bounds.x + x;
                    int gy = bounds.y + y;
                    if (gx >= 0 && gx < ip.getWidth() && gy >= 0 && gy < ip.getHeight()) {
                        ip.setf(gx, gy, label);
                    }
                }
            }
        }
    }
}
