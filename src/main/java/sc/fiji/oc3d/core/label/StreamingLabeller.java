package sc.fiji.oc3d.core.label;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.Arrays;

import sc.fiji.oc3d.core.progress.ProgressListener;

/**
 * Connected-component labelling that never allocates a whole-volume working
 * array.
 *
 * <h2>Why it exists</h2>
 *
 * The implementation being replaced, {@code Utilities.Counter3D}, flattens the
 * stack into {@code int[] imgArray}, then adds {@code int[] objID} and
 * {@code boolean[] isSurf} of the same length, then per-object voxel lists on
 * top. For a 1024x1024x50 stack that is roughly 985&nbsp;MB of working set
 * against a 105&nbsp;MB image, and {@code int length = width * height *
 * nbSlices} overflows above 2^31 voxels - the documented failure over 2048&nbsp;MB.
 *
 * <p>An ImageJ stack is already a list of per-slice processors, so flattening is
 * unnecessary. This labeller holds <b>two {@code int} label planes</b> and a
 * union-find sized by fragment count, and reads the source twice:
 *
 * <ol>
 *   <li><b>Pass 1</b> walks z&rarr;y&rarr;x, assigns each foreground voxel the
 *       smallest root among its <i>anterior</i> neighbours (or starts a new
 *       fragment), unions the roots it saw, and tallies voxels and edge contact
 *       per fragment.</li>
 *   <li>Fragments are resolved, filtered by size and edge contact, and numbered
 *       {@code 1..N} in ascending root order.</li>
 *   <li><b>Pass 2</b> re-walks the source and writes final labels straight into
 *       a correctly sized output stack.</li>
 * </ol>
 *
 * <p>Pass 2 exists so the output bit depth can be chosen from the <i>final</i>
 * object count rather than from a provisional maximum that is unknown until
 * pass 1 finishes. Re-deriving fragment starts is exact: a voxel starts a
 * fragment iff none of its anterior neighbours is foreground, which is a
 * property of the threshold mask alone and does not depend on union state.
 *
 * <h2>What is preserved, and why it is not negotiable</h2>
 *
 * <ul>
 *   <li><b>26-connectivity by default</b> - see {@link Connectivity}. Both
 *       implementations being replaced use it.</li>
 *   <li><b>{@code value >= threshold}, and never zero, is foreground</b> - see
 *       {@link #isForeground}. {@code Counter3D.imgArrayModifier()} zeroes every
 *       voxel below the threshold and then labels what is left, so zero is
 *       background whatever the threshold says.</li>
 *   <li><b>z&rarr;y&rarr;x traversal order</b>, matching the measurement pass.
 *       Floating-point summation is not associative, so a different order
 *       perturbs {@code Mean} and {@code StdDev} in their last bits and turns an
 *       exact-match test into noise. Do not parallelise this without a
 *       deterministic reduction.</li>
 *   <li><b>Labels numbered by first appearance.</b> {@code Counter3D} renumbers
 *       by ascending provisional id, and its provisional ids are handed out in
 *       scan order, so object 1 is the object whose first voxel is scanned
 *       first. Fragment roots here are the minimum fragment id in the component,
 *       which is the same ordering.</li>
 *   <li><b>Edge rule</b>: x=0, y=0, x=width-1, y=height-1, and - only when
 *       depth &gt; 1 - z=0 and z=depth-1. A single-slice stack has no z edge.</li>
 * </ul>
 *
 * <h2>Memory, stated honestly</h2>
 *
 * The labeller's own working set is two {@code int[width*height]} planes plus
 * three arrays sized by fragment count - not by voxel count. The output label
 * image is a separate, unavoidable allocation of the same size the existing
 * object map already produces (1 byte per voxel up to 255 objects). Nothing here
 * allocates a whole-volume {@code int} array, and every counter that could
 * exceed 2^31 is a {@code long}.
 *
 * <p>All methods are static and hold no state between calls; concurrent calls on
 * different images are safe.
 */
public final class StreamingLabeller {

    private static final int INITIAL_FRAGMENT_CAPACITY = 1024;

    private StreamingLabeller() {
        // Utility class.
    }

    /**
     * Is this voxel foreground?
     *
     * <p>{@code value >= threshold}, <b>and never zero</b>. The second half only
     * bites when the threshold is zero or negative, and it is what
     * {@code Counter3D} does: {@code imgArrayModifier()} zeroes every voxel below
     * the threshold and {@code findObjects()} then labels the non-zero remainder,
     * so a zero voxel is background regardless of what the threshold is set to.
     *
     * <p>Without this, a threshold of 0 makes the entire volume one object -
     * background included - which is how the difference was found: the migration's
     * equivalence harness runs an "all foreground" configuration at threshold 0,
     * and on a 16x16x8 fixture the labeller reported a single 2048-voxel object
     * with {@code Min = 0} against the shipped plugin's 16.
     *
     * <p>The mcib3d path this also replaces did <em>not</em> exclude zero, so
     * 32-bit and multichannel input at threshold 0 previously reported the whole
     * volume as one object. That is a deliberate correction rather than an
     * oversight: measuring the background as an object is not a useful answer, and
     * one engine can only have one rule.
     *
     * <p>{@code NaN} fails {@code >=} and is therefore background, on both sides.
     */
    static boolean isForeground(float value, double threshold) {
        return value >= threshold && value != 0.0f;
    }

    /** Equivalent to {@link #label(ImagePlus, LabelParameters, ProgressListener)} with no progress. */
    public static LabelResult label(ImagePlus source, LabelParameters parameters) {
        return label(source, parameters, ProgressListener.NONE);
    }

    /**
     * Labels the connected components of {@code source}.
     *
     * @param source single 3D volume; hyperstacks and RGB are rejected by
     *               {@link LabelImages#requireLabellableVolume}
     * @param parameters detection settings, or {@code null} for the defaults
     * @param progress progress sink, or {@code null} for none
     * @return the label image and per-object voxel counts
     */
    public static LabelResult label(ImagePlus source,
                                    LabelParameters parameters,
                                    ProgressListener progress) {
        LabelImages.requireLabellableVolume(source, "source");
        LabelParameters safe = parameters == null ? new LabelParameters() : parameters;
        ProgressListener sink = progress == null ? ProgressListener.NONE : progress;

        ImageStack stack = source.getStack();
        int width = source.getWidth();
        int height = source.getHeight();
        int depth = stack.getSize();
        int pixelsPerSlice = width * height;
        boolean twentySix = safe.connectivity() == Connectivity.TWENTY_SIX;
        double threshold = safe.threshold();

        Fragments fragments = new Fragments();
        int[] previousPlane = new int[pixelsPerSlice];
        int[] currentPlane = new int[pixelsPerSlice];

        // ── Pass 1: fragment assignment and union ────────────────────────────
        for (int z = 0; z < depth; z++) {
            ImageProcessor slice = requireSlice(stack, z, width, height);
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = rowOffset + x;
                    if (!isForeground(slice.getf(index), threshold)) {
                        currentPlane[index] = 0;
                        continue;
                    }

                    int root = 0;
                    if (z > 0) {
                        if (twentySix) {
                            int firstY = y > 0 ? y - 1 : 0;
                            int lastY = y < height - 1 ? y + 1 : height - 1;
                            int firstX = x > 0 ? x - 1 : 0;
                            int lastX = x < width - 1 ? x + 1 : width - 1;
                            for (int ny = firstY; ny <= lastY; ny++) {
                                int base = ny * width;
                                for (int nx = firstX; nx <= lastX; nx++) {
                                    root = fragments.mergeInto(root, previousPlane[base + nx]);
                                }
                            }
                        } else {
                            root = fragments.mergeInto(root, previousPlane[index]);
                        }
                    }
                    if (y > 0) {
                        int base = index - width;
                        if (twentySix) {
                            if (x > 0) root = fragments.mergeInto(root, currentPlane[base - 1]);
                            root = fragments.mergeInto(root, currentPlane[base]);
                            if (x < width - 1) root = fragments.mergeInto(root, currentPlane[base + 1]);
                        } else {
                            root = fragments.mergeInto(root, currentPlane[base]);
                        }
                    }
                    if (x > 0) {
                        root = fragments.mergeInto(root, currentPlane[index - 1]);
                    }

                    if (root == 0) {
                        root = fragments.create();
                    }
                    currentPlane[index] = root;
                    fragments.voxels[root]++;
                    if (isEdgeVoxel(x, y, z, width, height, depth)) {
                        fragments.atEdge[root] = true;
                    }
                }
            }
            int[] swap = previousPlane;
            previousPlane = currentPlane;
            currentPlane = swap;
            sink.progress("Finding structures", z + 1L, depth);
        }

        // ── Resolve, filter, number ─────────────────────────────────────────
        int fragmentCount = fragments.count;
        long[] rootVoxels = new long[fragmentCount + 1];
        boolean[] rootAtEdge = new boolean[fragmentCount + 1];
        for (int id = 1; id <= fragmentCount; id++) {
            int root = fragments.find(id);
            rootVoxels[root] += fragments.voxels[id];
            if (fragments.atEdge[id]) rootAtEdge[root] = true;
        }

        int[] finalLabelOfRoot = new int[fragmentCount + 1];
        int objectCount = 0;
        long minSize = safe.minSize();
        long maxSize = safe.maxSize();
        boolean excludeOnEdges = safe.excludeOnEdges();
        for (int id = 1; id <= fragmentCount; id++) {
            if (fragments.find(id) != id) continue;
            long voxels = rootVoxels[id];
            if (voxels <= 0L) continue;
            if (voxels < minSize || voxels > maxSize) continue;
            if (excludeOnEdges && rootAtEdge[id]) continue;
            finalLabelOfRoot[id] = ++objectCount;
        }

        long[] voxelCounts = new long[objectCount + 1];
        for (int id = 1; id <= fragmentCount; id++) {
            int label = finalLabelOfRoot[id];
            if (label > 0) voxelCounts[label] = rootVoxels[id];
        }

        // ── Pass 2: write final labels ──────────────────────────────────────
        Arrays.fill(previousPlane, 0);
        Arrays.fill(currentPlane, 0);
        ImageStack out = new ImageStack(width, height);
        int fragment = 0;
        for (int z = 0; z < depth; z++) {
            ImageProcessor slice = requireSlice(stack, z, width, height);
            ImageProcessor output = LabelImages.labelProcessor(width, height, objectCount);
            for (int y = 0; y < height; y++) {
                int rowOffset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = rowOffset + x;
                    if (!isForeground(slice.getf(index), threshold)) {
                        currentPlane[index] = 0;
                        continue;
                    }

                    int root = anteriorRoot(previousPlane, currentPlane, index,
                            x, y, z, width, height, twentySix);
                    if (root == 0) {
                        // Same condition that created a fragment in pass 1, and
                        // fragments were created in this same scan order, so the
                        // counter reproduces pass 1's id exactly.
                        root = fragments.find(++fragment);
                    }
                    currentPlane[index] = root;
                    int label = finalLabelOfRoot[root];
                    if (label != 0) {
                        output.setf(index, label);
                    }
                }
            }
            out.addSlice(stack.getSliceLabel(z + 1), output);
            int[] swap = previousPlane;
            previousPlane = currentPlane;
            currentPlane = swap;
            sink.progress("Labelling structures", z + 1L, depth);
        }

        ImagePlus labelImage = new ImagePlus(source.getTitle(), out);
        Calibration calibration = source.getCalibration();
        if (calibration != null) {
            labelImage.setCalibration(calibration.copy());
        }
        labelImage.setDisplayRange(0.0, Math.max(1, objectCount));
        return new LabelResult(labelImage, objectCount, voxelCounts, safe.connectivity());
    }

    /**
     * Root shared by every foreground anterior neighbour of a voxel.
     *
     * <p>Returns the first non-zero it finds and stops. That is safe because all
     * anterior neighbours of a voxel are adjacent to it, hence in one component,
     * hence carry one root once pass 1 has finished merging.
     */
    private static int anteriorRoot(int[] previousPlane,
                                    int[] currentPlane,
                                    int index,
                                    int x,
                                    int y,
                                    int z,
                                    int width,
                                    int height,
                                    boolean twentySix) {
        if (z > 0) {
            if (twentySix) {
                int firstY = y > 0 ? y - 1 : 0;
                int lastY = y < height - 1 ? y + 1 : height - 1;
                int firstX = x > 0 ? x - 1 : 0;
                int lastX = x < width - 1 ? x + 1 : width - 1;
                for (int ny = firstY; ny <= lastY; ny++) {
                    int base = ny * width;
                    for (int nx = firstX; nx <= lastX; nx++) {
                        int candidate = previousPlane[base + nx];
                        if (candidate != 0) return candidate;
                    }
                }
            } else if (previousPlane[index] != 0) {
                return previousPlane[index];
            }
        }
        if (y > 0) {
            int base = index - width;
            if (twentySix) {
                if (x > 0 && currentPlane[base - 1] != 0) return currentPlane[base - 1];
                if (currentPlane[base] != 0) return currentPlane[base];
                if (x < width - 1 && currentPlane[base + 1] != 0) return currentPlane[base + 1];
            } else if (currentPlane[base] != 0) {
                return currentPlane[base];
            }
        }
        if (x > 0 && currentPlane[index - 1] != 0) return currentPlane[index - 1];
        return 0;
    }

    private static boolean isEdgeVoxel(int x, int y, int z, int width, int height, int depth) {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return true;
        return depth > 1 && (z == 0 || z == depth - 1);
    }

    private static ImageProcessor requireSlice(ImageStack stack, int z, int width, int height) {
        ImageProcessor processor = stack.getProcessor(z + 1);
        if (processor == null || processor.getPixelCount() < width * height) {
            throw new IllegalArgumentException("source stack has an invalid slice at index "
                    + (z + 1) + " (expected " + width + "x" + height + ").");
        }
        return processor;
    }

    /**
     * Union-find over fragments, with the invariant that <b>a component's root is
     * the smallest fragment id in it</b>.
     *
     * <p>That invariant is what makes final labels match {@code Counter3D}:
     * fragment ids are handed out in z&rarr;y&rarr;x scan order, so the smallest
     * id in a component is the fragment containing its first-scanned voxel, and
     * numbering roots in ascending order numbers objects by first appearance.
     * Union by rank would break it, so this unions by id and relies on path
     * compression alone.
     */
    private static final class Fragments {
        int[] parent = new int[INITIAL_FRAGMENT_CAPACITY];
        long[] voxels = new long[INITIAL_FRAGMENT_CAPACITY];
        boolean[] atEdge = new boolean[INITIAL_FRAGMENT_CAPACITY];
        int count;

        int create() {
            ensureCapacity(count + 2);
            count++;
            parent[count] = count;
            voxels[count] = 0L;
            atEdge[count] = false;
            return count;
        }

        int find(int id) {
            int root = id;
            while (parent[root] != root) {
                parent[root] = parent[parent[root]];
                root = parent[root];
            }
            return root;
        }

        /**
         * Folds one neighbour's fragment into the root being built for the
         * current voxel.
         *
         * @param root root accumulated so far, or 0 if none yet
         * @param neighbour raw plane value of a neighbour, possibly stale or 0
         * @return the new accumulated root
         */
        int mergeInto(int root, int neighbour) {
            if (neighbour == 0) return root;
            int other = find(neighbour);
            if (root == 0 || root == other) return other;
            if (other < root) {
                parent[root] = other;
                return other;
            }
            parent[other] = root;
            return root;
        }

        private void ensureCapacity(int required) {
            if (required <= parent.length) return;
            int length = parent.length;
            while (length < required) {
                length = length > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : length * 2;
            }
            parent = Arrays.copyOf(parent, length);
            voxels = Arrays.copyOf(voxels, length);
            atEdge = Arrays.copyOf(atEdge, length);
        }
    }
}
