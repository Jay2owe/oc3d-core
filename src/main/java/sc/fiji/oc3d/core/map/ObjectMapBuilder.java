package sc.fiji.oc3d.core.map;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.TextRoi;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

import sc.fiji.oc3d.core.image.ImageOps;
import sc.fiji.oc3d.core.label.LabelImages;

/**
 * Builds the companion maps 3D Objects Counter shows alongside the statistics
 * table: objects, surfaces, centroids, centres of mass.
 *
 * <p>Each map is a full-volume allocation, so each one is guarded: before
 * allocating, {@link #requireOptionalMapMemory} estimates the cost and throws
 * {@link OptionalMapMemoryException} if the heap cannot take it <em>plus</em> a
 * reserve. That is deliberate - a user who ticked four optional maps on a large
 * stack should get a message naming the map and the sizes, not an
 * {@code OutOfMemoryError} from somewhere unrelated a few seconds later. The
 * caller catches it, tells the user which map to untick, and keeps the
 * statistics it already computed.
 */
public final class ObjectMapBuilder {

    /**
     * Historic cap on how many labels get a number overlay.
     *
     * <p><b>Not enforced.</b> The overlay is always drawn - see
     * {@code ObjectMapBuilderTest.objectMapAlwaysAddsNumberOverlayAboveConfiguredDisplayLimit},
     * which pins that decision. The constant survives because callers and tests
     * reference it; it is documented rather than quietly deleted so nobody
     * re-derives the cap from its name.
     */
    public static final String MAX_OVERLAY_LABELS_PROPERTY =
            "sc.fiji.oc3d.core.maxOverlayLabels";
    /**
     * {@code ImagePlus} property key - not a system property - set to
     * {@link Boolean#TRUE} on a map whose overlay was suppressed.
     */
    public static final String OVERLAY_SKIPPED_PROPERTY =
            "sc.fiji.oc3d.core.overlaySkipped";
    /** {@code ImagePlus} property key holding the reason, when one was recorded. */
    public static final String OVERLAY_SKIPPED_REASON_PROPERTY =
            "sc.fiji.oc3d.core.overlaySkippedReason";
    /**
     * System property overriding the free heap an optional map must leave
     * behind, in bytes. Defaults to one sixteenth of the maximum heap, clamped
     * to 64-256 MiB.
     */
    public static final String OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY =
            "sc.fiji.oc3d.core.optionalMapMemoryReserveBytes";

    private static final long MIN_DEFAULT_OPTIONAL_MAP_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DEFAULT_OPTIONAL_MAP_RESERVE_BYTES = 256L * 1024L * 1024L;
    private static final Font LABEL_FONT = new Font("Arial", Font.PLAIN, 10);
    private static final Color LABEL_COLOR = Color.WHITE;

    private ObjectMapBuilder() {}

    /** Thrown before allocating a map the heap cannot hold. */
    public static final class OptionalMapMemoryException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String mapName;
        private final long estimatedBytes;
        private final long availableBytes;
        private final long reserveBytes;

        OptionalMapMemoryException(String mapName,
                                   long estimatedBytes,
                                   long availableBytes,
                                   long reserveBytes) {
            super("not enough memory for optional " + safeMapName(mapName)
                    + " map (estimated " + formatBytes(estimatedBytes)
                    + ", available " + formatBytes(availableBytes)
                    + ", required free reserve " + formatBytes(reserveBytes) + ")");
            this.mapName = safeMapName(mapName);
            this.estimatedBytes = estimatedBytes;
            this.availableBytes = availableBytes;
            this.reserveBytes = reserveBytes;
        }

        /** Which map could not be built, e.g. {@code "Surfaces"}. */
        public String mapName() {
            return mapName;
        }

        public long estimatedBytes() {
            return estimatedBytes;
        }

        public long availableBytes() {
            return availableBytes;
        }

        public long reserveBytes() {
            return reserveBytes;
        }
    }

    /** @return why a map's overlay was skipped, or {@code null} if it was not */
    public static String overlaySkippedReason(ImagePlus image) {
        if (image == null || !Boolean.TRUE.equals(image.getProperty(OVERLAY_SKIPPED_PROPERTY))) {
            return null;
        }
        Object reason = image.getProperty(OVERLAY_SKIPPED_REASON_PROPERTY);
        return reason instanceof String ? (String) reason : "Object-number overlay skipped.";
    }

    /**
     * Walks the cause chain for an {@link OptionalMapMemoryException}.
     *
     * <p>Needed because the throw happens deep inside a map builder and arrives
     * at the plugin wrapped in whatever the worker thread wraps it in. The
     * distinction matters: this one is a "untick a map" message, any other
     * failure is a bug report.
     */
    public static boolean isMemoryGuardFailure(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof OptionalMapMemoryException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** Objects map on a copy of {@code labelImage}, leaving the input untouched. */
    public static ImagePlus objectMap(ImagePlus labelImage, ResultsTable stats, String sourceTitle) {
        if (labelImage == null || labelImage.getStack() == null) return null;
        requireOptionalMapMemory(labelImage, "Objects", maxLabel(labelImage));
        ImagePlus out = ImageOps.duplicateThreadSafe(labelImage);
        return objectMapInPlace(out, stats, sourceTitle);
    }

    /**
     * Objects map by re-titling {@code labelImage} itself.
     *
     * <p>The label image already holds exactly the pixels the objects map needs,
     * so a caller that has no further use for it hands it over instead of paying
     * for a second full-volume copy.
     *
     * @return the same instance that was passed in
     */
    public static ImagePlus objectMapInPlace(ImagePlus labelImage, ResultsTable stats, String sourceTitle) {
        if (labelImage == null || labelImage.getStack() == null) return null;
        labelImage.setTitle("Objects map of " + safeTitle(sourceTitle, labelImage));
        labelImage.setDisplayRange(0, Math.max(1, maxLabel(labelImage)));
        addNumberOverlay(labelImage, stats, "X", "Y", "Z");
        return labelImage;
    }

    public static ImagePlus surfaceMap(ImagePlus labelImage, String sourceTitle) {
        return surfaceMap(labelImage, null, sourceTitle);
    }

    /**
     * Surface map: every voxel of an object that has at least one neighbour of a
     * different label, or lies on an image border.
     *
     * <p>The neighbourhood is the 6 face neighbours, which is the surface
     * definition the statistics table's {@code Nb of surf. voxels} column
     * counts, so map and table agree.
     */
    public static ImagePlus surfaceMap(ImagePlus labelImage, ResultsTable stats, String sourceTitle) {
        if (labelImage == null || labelImage.getStack() == null) return null;
        ImageStack labels = labelImage.getStack();
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labels.size();
        int maxLabel = maxLabel(labelImage);
        requireOptionalMapMemory(labelImage, "Surfaces", maxLabel);
        ImageStack out = new ImageStack(width, height);

        try {
            for (int slice = 1; slice <= depth; slice++) {
                ImageProcessor fp = LabelImages.labelProcessor(width, height, maxLabel);
                ImageProcessor lp = labels.getProcessor(slice);
                ImageProcessor previous = slice <= 1 ? null : labels.getProcessor(slice - 1);
                ImageProcessor next = slice >= depth ? null : labels.getProcessor(slice + 1);
                if (lp != null) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int label = labelFromPixel(lp.getf(x, y));
                            if (label > 0 && isSurfaceVoxel(lp, previous, next,
                                    x, y, width, height, label)) {
                                fp.setf(x, y, label);
                            }
                        }
                    }
                }
                out.addSlice(labels.getSliceLabel(slice), fp);
            }

            ImagePlus map = mapImage(labelImage, out,
                    "Surfaces map of " + safeTitle(sourceTitle, labelImage), maxLabel);
            addNumberOverlay(map, stats, "X", "Y", "Z");
            return map;
        } catch (OutOfMemoryError oom) {
            releaseStack(out);
            throw oom;
        }
    }

    /** One voxel per object at its geometric centroid, from the {@code X/Y/Z} columns. */
    public static ImagePlus centroidMap(ImagePlus labelImage, ResultsTable stats, String sourceTitle) {
        return pointMap(labelImage, stats, "X", "Y", "Z",
                "Centroids",
                "Centroids map of " + safeTitle(sourceTitle, labelImage));
    }

    /** One voxel per object at its intensity-weighted centre, from {@code XM/YM/ZM}. */
    public static ImagePlus centerOfMassMap(ImagePlus labelImage, ResultsTable stats, String sourceTitle) {
        return pointMap(labelImage, stats, "XM", "YM", "ZM",
                "Centers of mass",
                "Centers of mass map of " + safeTitle(sourceTitle, labelImage));
    }

    private static ImagePlus pointMap(ImagePlus labelImage,
                                      ResultsTable stats,
                                      String xColumn,
                                      String yColumn,
                                      String zColumn,
                                      String mapName,
                                      String title) {
        if (labelImage == null || labelImage.getStack() == null) return null;
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelImage.getStackSize();
        int maxLabel = maxLabel(labelImage);
        requireOptionalMapMemory(labelImage, mapName, maxLabel);
        ImageStack out = new ImageStack(width, height);
        try {
            for (int slice = 0; slice < depth; slice++) {
                out.addSlice(labelImage.getStack().getSliceLabel(slice + 1),
                        LabelImages.labelProcessor(width, height, maxLabel));
            }

            if (stats != null) {
                for (int row = 0; row < stats.size(); row++) {
                    int label = labelForRow(stats, row);
                    double x = valueOrNaN(stats, xColumn, row);
                    double y = valueOrNaN(stats, yColumn, row);
                    double z = valueOrNaN(stats, zColumn, row);
                    if (label <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                            || !Double.isFinite(z)) {
                        continue;
                    }
                    int xi = coordinateToIndex(x, width);
                    int yi = coordinateToIndex(y, height);
                    int zi = coordinateToIndex(z, depth);
                    if (xi < 0 || yi < 0 || zi < 0) continue;
                    out.getProcessor(zi + 1).setf(xi, yi, label);
                }
            }

            ImagePlus map = mapImage(labelImage, out, title, maxLabel);
            addNumberOverlay(map, stats, xColumn, yColumn, zColumn);
            return map;
        } catch (OutOfMemoryError oom) {
            releaseStack(out);
            throw oom;
        }
    }

    private static boolean isSurfaceVoxel(ImageProcessor here,
                                          ImageProcessor previous,
                                          ImageProcessor next,
                                          int x,
                                          int y,
                                          int width,
                                          int height,
                                          int label) {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1
                || previous == null || next == null || here == null) {
            return true;
        }
        return labelFromPixel(here.getf(x - 1, y)) != label
                || labelFromPixel(here.getf(x + 1, y)) != label
                || labelFromPixel(here.getf(x, y - 1)) != label
                || labelFromPixel(here.getf(x, y + 1)) != label
                || labelFromPixel(previous.getf(x, y)) != label
                || labelFromPixel(next.getf(x, y)) != label;
    }

    private static ImagePlus mapImage(ImagePlus reference, ImageStack stack, String title, int maxLabel) {
        ImagePlus out = new ImagePlus(title, stack);
        out.setDimensions(Math.max(1, reference.getNChannels()),
                Math.max(1, reference.getNSlices()),
                Math.max(1, reference.getNFrames()));
        if (reference.isHyperStack()) {
            out.setOpenAsHyperStack(true);
        }
        if (reference.getCalibration() != null) {
            out.setCalibration(reference.getCalibration().copy());
        }
        out.setDisplayRange(0, Math.max(1, maxLabel));
        return out;
    }

    /**
     * Rejects a map that will not fit, before any of it is allocated.
     *
     * @param mapName name used in the message
     * @throws OptionalMapMemoryException naming the map and the three sizes
     */
    static void requireOptionalMapMemory(ImagePlus labelImage,
                                         String mapName,
                                         int maxLabel) {
        long estimatedBytes = estimateStackBytes(labelImage, maxLabel);
        if (estimatedBytes <= 0L) return;
        long availableBytes = availableMemoryBytes();
        long reserveBytes = optionalMapMemoryReserveBytes();
        long requiredBytes = saturatedAdd(estimatedBytes, reserveBytes);
        if (availableBytes >= 0L && availableBytes < requiredBytes) {
            throw new OptionalMapMemoryException(mapName, estimatedBytes,
                    availableBytes, reserveBytes);
        }
    }

    private static long estimateStackBytes(ImagePlus image, int maxLabel) {
        if (image == null) return 0L;
        long width = Math.max(0, image.getWidth());
        long height = Math.max(0, image.getHeight());
        long depth = Math.max(0, image.getStackSize());
        long pixels = saturatedMultiply(saturatedMultiply(width, height), depth);
        long bytes = saturatedMultiply(pixels, bytesPerLabelPixel(maxLabel));
        // A quarter on top for the ImageStack's own per-slice bookkeeping.
        return saturatedAdd(bytes, bytes / 4L);
    }

    private static int bytesPerLabelPixel(int maxLabel) {
        if (maxLabel <= 255) return 1;
        if (maxLabel <= 65535) return 2;
        return 4;
    }

    private static long availableMemoryBytes() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        if (max == Long.MAX_VALUE) return -1L;
        return Math.max(0L, max - used);
    }

    private static long optionalMapMemoryReserveBytes() {
        String configured = System.getProperty(OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            try {
                long parsed = Long.parseLong(configured.trim());
                return parsed < 0L ? 0L : parsed;
            } catch (NumberFormatException invalidValue) {
                // Fall through to the default reserve.
            }
        }
        long max = Runtime.getRuntime().maxMemory();
        if (max <= 0L || max == Long.MAX_VALUE) {
            return MIN_DEFAULT_OPTIONAL_MAP_RESERVE_BYTES;
        }
        long scaled = max / 16L;
        if (scaled < MIN_DEFAULT_OPTIONAL_MAP_RESERVE_BYTES) {
            return MIN_DEFAULT_OPTIONAL_MAP_RESERVE_BYTES;
        }
        return Math.min(MAX_DEFAULT_OPTIONAL_MAP_RESERVE_BYTES, scaled);
    }

    private static long saturatedMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) return 0L;
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        return a * b;
    }

    private static long saturatedAdd(long a, long b) {
        if (a >= Long.MAX_VALUE || b >= Long.MAX_VALUE) return Long.MAX_VALUE;
        if (Long.MAX_VALUE - a < b) return Long.MAX_VALUE;
        return a + b;
    }

    private static void releaseStack(ImageStack stack) {
        if (stack == null) return;
        try {
            while (stack.size() > 0) {
                stack.deleteLastSlice();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup after an allocation failure.
        }
    }

    private static int maxLabel(ImagePlus image) {
        int max = 0;
        if (image == null || image.getStack() == null) return max;
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.size(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                max = Math.max(max, labelFromPixel(processor.getf(i)));
            }
        }
        return max;
    }

    /**
     * Maps a table coordinate onto a stack index.
     *
     * <p>Accepts both conventions: 0-based, as the statistics table writes them,
     * and 1-based, as an ImageJ slice number. Guessing is safe here because the
     * two only overlap where both readings are in range, and there the 0-based
     * one wins - which is what the core's own tables use.
     */
    private static int coordinateToIndex(double value, int size) {
        if (size <= 0 || !Double.isFinite(value)) return -1;
        int rounded = (int) Math.round(value);
        if (rounded >= 0 && rounded < size) return rounded;
        if (rounded >= 1 && rounded <= size) return rounded - 1;
        return -1;
    }

    private static int labelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            if (Double.isFinite(label) && label > 0 && label <= Integer.MAX_VALUE) {
                return (int) Math.round(label);
            }
        } catch (RuntimeException missingLabelColumn) {
            // Older/partial tables may not expose a Label column; row order is the fallback.
        }
        return row + 1;
    }

    private static void addNumberOverlay(ImagePlus image,
                                         ResultsTable stats,
                                         String xColumn,
                                         String yColumn,
                                         String zColumn) {
        if (image == null || stats == null || stats.size() == 0) return;
        image.setProperty(OVERLAY_SKIPPED_PROPERTY, null);
        image.setProperty(OVERLAY_SKIPPED_REASON_PROPERTY, null);
        Overlay overlay = new Overlay();
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = Math.max(1, image.getStackSize());
        for (int row = 0; row < stats.size(); row++) {
            int label = labelForRow(stats, row);
            double x = valueOrNaN(stats, xColumn, row);
            double y = valueOrNaN(stats, yColumn, row);
            double z = valueOrNaN(stats, zColumn, row);
            if (label <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                continue;
            }
            int xi = coordinateToIndex(x, width);
            int yi = coordinateToIndex(y, height);
            int zi = coordinateToIndex(z, depth);
            if (xi < 0 || yi < 0 || zi < 0) continue;

            TextRoi roi = new TextRoi(xi, yi, Integer.toString(label), LABEL_FONT);
            roi.setStrokeColor(LABEL_COLOR);
            roi.setName(Integer.toString(label));
            roi.setPosition(zi + 1);
            overlay.add(roi);
        }
        if (overlay.size() > 0) {
            image.setOverlay(overlay);
        }
    }

    private static double valueOrNaN(ResultsTable table, String column, int row) {
        try {
            return table.getValue(column, row);
        } catch (RuntimeException missingColumn) {
            return Double.NaN;
        }
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static String safeMapName(String mapName) {
        if (mapName == null || mapName.trim().isEmpty()) return "output";
        String trimmed = mapName.trim();
        if (trimmed.endsWith(" map")) {
            return trimmed.substring(0, trimmed.length() - 4).trim();
        }
        return trimmed;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) return "unknown";
        double mib = bytes / (1024.0 * 1024.0);
        if (mib >= 1.0) {
            return String.format(Locale.ROOT, "%.1f MiB", Double.valueOf(mib));
        }
        double kib = bytes / 1024.0;
        return String.format(Locale.ROOT, "%.1f KiB", Double.valueOf(kib));
    }

    private static String safeTitle(String sourceTitle, ImagePlus fallback) {
        if (sourceTitle != null && !sourceTitle.trim().isEmpty()) return sourceTitle;
        if (fallback == null || fallback.getTitle() == null || fallback.getTitle().trim().isEmpty()) {
            return "<untitled>";
        }
        return fallback.getTitle();
    }
}
