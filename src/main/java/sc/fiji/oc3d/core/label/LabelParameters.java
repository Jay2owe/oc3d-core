package sc.fiji.oc3d.core.label;

/**
 * Detection settings shared by every labelling engine in the family.
 *
 * <p>Mutable and fluent; instances are cheap and not meant to be shared between
 * threads. {@link #copy()} exists so a caller can hand an engine a snapshot.
 *
 * <p>The defaults reproduce the shipped behaviour of 3D Objects Counter+ with no
 * filters applied: everything at or above the threshold is foreground, no size
 * limits, edge-touching objects kept.
 */
public final class LabelParameters {

    private double threshold = 1.0;
    private Connectivity connectivity = Connectivity.TWENTY_SIX;
    private long minSize = 0L;
    private long maxSize = Long.MAX_VALUE;
    private boolean excludeOnEdges;

    /**
     * Intensity at or above which a voxel is foreground.
     *
     * <p>The comparison is {@code value >= threshold}, matching
     * {@code Counter3D.imgArrayModifier()}, which zeroes every voxel with
     * {@code imgArray[i] < thr}. An off-by-one here shifts object boundaries on
     * every image, so it is stated rather than implied.
     */
    public double threshold() {
        return threshold;
    }

    public LabelParameters threshold(double value) {
        this.threshold = value;
        return this;
    }

    /** @see Connectivity */
    public Connectivity connectivity() {
        return connectivity;
    }

    public LabelParameters connectivity(Connectivity value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "connectivity must not be null (connectivity=null; expected SIX or TWENTY_SIX).");
        }
        this.connectivity = value;
        return this;
    }

    /** Smallest object kept, in voxels. Objects below this are erased, not renumbered around. */
    public long minSize() {
        return minSize;
    }

    public LabelParameters minSize(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "minSize must not be negative (minSize=" + value + ").");
        }
        this.minSize = value;
        return this;
    }

    /** Largest object kept, in voxels. */
    public long maxSize() {
        return maxSize;
    }

    public LabelParameters maxSize(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "maxSize must not be negative (maxSize=" + value + ").");
        }
        this.maxSize = value;
        return this;
    }

    /**
     * Whether to discard objects touching the edge of the volume.
     *
     * <p>The edge is x=0, y=0, x=width-1, y=height-1, and - only when the stack
     * has more than one slice - z=0 and z=depth-1. A single-slice stack has no
     * z edge, matching {@code Counter3D.findObjects()}.
     */
    public boolean excludeOnEdges() {
        return excludeOnEdges;
    }

    public LabelParameters excludeOnEdges(boolean value) {
        this.excludeOnEdges = value;
        return this;
    }

    /** @return an independent copy carrying the same settings */
    public LabelParameters copy() {
        return new LabelParameters()
                .threshold(threshold)
                .connectivity(connectivity)
                .minSize(minSize)
                .maxSize(maxSize)
                .excludeOnEdges(excludeOnEdges);
    }

    @Override
    public String toString() {
        return "LabelParameters[threshold=" + threshold
                + ", connectivity=" + connectivity.neighbourCount()
                + ", minSize=" + minSize
                + ", maxSize=" + (maxSize == Long.MAX_VALUE ? "unbounded" : Long.toString(maxSize))
                + ", excludeOnEdges=" + excludeOnEdges + "]";
    }
}
