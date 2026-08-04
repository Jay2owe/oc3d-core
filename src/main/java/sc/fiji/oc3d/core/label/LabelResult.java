package sc.fiji.oc3d.core.label;

import ij.ImagePlus;

/**
 * What a labelling engine returns: a dense {@code 1..N} label image plus the
 * facts about the run that a caller would otherwise have to recompute.
 *
 * <p>Deliberately <b>not</b> an {@code ij.measure.ResultsTable}. A core that
 * returns the host's table type cannot be embedded in a plugin whose table has
 * a different column layout, which is the whole reason this module exists.
 * Table construction is presentation and belongs to the plugin.
 */
public final class LabelResult {

    private final ImagePlus labelImage;
    private final int objectCount;
    private final long[] voxelCounts;
    private final Connectivity connectivity;

    LabelResult(ImagePlus labelImage,
                int objectCount,
                long[] voxelCounts,
                Connectivity connectivity) {
        this.labelImage = labelImage;
        this.objectCount = objectCount;
        this.voxelCounts = voxelCounts;
        this.connectivity = connectivity;
    }

    /**
     * Label image with values {@code 0} (background) and {@code 1..objectCount},
     * carrying a copy of the source calibration.
     *
     * <p>Bit depth follows the object count, not the source: 8-bit up to 255
     * objects, 16-bit up to 65,535, 32-bit float beyond. This is the same ladder
     * the existing map builders use, so downstream code sees no change.
     */
    public ImagePlus labelImage() {
        return labelImage;
    }

    /** Number of objects surviving the size and edge filters. */
    public int objectCount() {
        return objectCount;
    }

    /** @see Connectivity - recorded so it can be written into output headers. */
    public Connectivity connectivity() {
        return connectivity;
    }

    /**
     * Voxel count of one object.
     *
     * @param label 1-based label, as it appears in {@link #labelImage()}
     * @return the object's voxel count, or 0 for a label outside {@code 1..objectCount}
     */
    public long voxelCount(int label) {
        if (label < 1 || label > objectCount) return 0L;
        return voxelCounts[label];
    }

    /** @return total foreground voxels across all surviving objects */
    public long totalObjectVoxels() {
        long total = 0L;
        for (int label = 1; label <= objectCount; label++) {
            total += voxelCounts[label];
        }
        return total;
    }
}
