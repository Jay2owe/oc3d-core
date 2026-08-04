package sc.fiji.oc3d.core.oracle;

import Utilities.Counter3D;

import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

import sc.fiji.oc3d.core.label.LabelParameters;
import sc.fiji.oc3d.core.label.LabelResult;
import sc.fiji.oc3d.core.label.StreamingLabeller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Differential test of {@link StreamingLabeller} against the implementation it
 * replaces, {@code Utilities.Counter3D} from sc.fiji:3D_Objects_Counter 2.0.1.
 *
 * <p><b>Excluded from the default build.</b> {@code 3D_Objects_Counter} is
 * GPLv3, and oc3d-core exists precisely so that nothing GPL is linked. The
 * dependency lives in an opt-in Maven profile, test-scoped, and this package is
 * excluded by surefire unless that profile is active:
 *
 * <pre>mvn -Poracle test</pre>
 *
 * <p>Hand-written expectations can encode the same misunderstanding twice. This
 * test cannot: it asserts that two independent implementations produce the same
 * label image, voxel for voxel, including the object <i>numbering</i>, over a
 * randomised corpus. That is the evidence behind the claim that the migration
 * changes no user-visible count.
 *
 * <p>What is being asserted about numbering: {@code Counter3D} renumbers by
 * ascending provisional id and hands provisional ids out in z&rarr;y&rarr;x scan
 * order, so its object 1 is the object whose first voxel is scanned first.
 * {@link StreamingLabeller} keeps the same invariant by construction. If that
 * ever stops holding, every row of every published table shifts, so it is
 * asserted as exact equality rather than as a partition comparison.
 */
public class Counter3DOracleTest {

    @BeforeClass
    public static void keepCounter3DHeadless() {
        // Counter3D's constructor reads this Pref and defaults it to TRUE, then
        // findObjects() calls ImagePlus.show() on the masked image. In a headless
        // test that is a hang or a HeadlessException, not a failure message.
        Prefs.set("3D-OC-Options_showMaskedImg.boolean", false);
        Prefs.set("3D-OC-Options_closeImg.boolean", false);
    }

    @Test
    public void agreesOnBlobCorpus() {
        for (int seed = 1; seed <= 12; seed++) {
            ImagePlus volume = blobs(28, 24, 7, 25, 3, seed);
            assertSameLabelling("blobs seed=" + seed, volume, 128, 0, Integer.MAX_VALUE, false);
        }
    }

    @Test
    public void agreesOnSpeckleCorpus() {
        double[] fractions = {0.02, 0.08, 0.2, 0.45, 0.8};
        for (int i = 0; i < fractions.length; i++) {
            ImagePlus volume = speckle(20, 18, 6, fractions[i], 100 + i);
            assertSameLabelling("speckle fraction=" + fractions[i], volume,
                    128, 0, Integer.MAX_VALUE, false);
        }
    }

    @Test
    public void agreesOnSingleSliceInput() {
        for (int seed = 1; seed <= 5; seed++) {
            ImagePlus volume = speckle(30, 30, 1, 0.25, 200 + seed);
            assertSameLabelling("2D speckle seed=" + seed, volume,
                    128, 0, Integer.MAX_VALUE, false);
        }
    }

    @Test
    public void agreesOnStructuresThatMergeLateInTheScan() {
        // Shapes whose arms are labelled separately and only join near the end
        // of the scan - the case that exercises the union path in both
        // implementations, and the one a naive labeller gets wrong.
        ImagePlus volume = fromAscii(new String[][] {
                {"X.....X",
                 "X.....X",
                 "X.....X",
                 "X.....X",
                 "XXXXXXX"},
                {".......",
                 ".......",
                 ".......",
                 ".......",
                 "......."},
                {"X.X.X.X",
                 ".......",
                 "X.X.X.X",
                 ".......",
                 "XXXXXXX"},
        });
        assertSameLabelling("late merge", volume, 128, 0, Integer.MAX_VALUE, false);
    }

    @Test
    public void agreesUnderSizeFilters() {
        ImagePlus volume = blobs(26, 26, 6, 30, 2, 55);
        int[] minSizes = {0, 1, 2, 5, 20, 100};
        for (int i = 0; i < minSizes.length; i++) {
            assertSameLabelling("minSize=" + minSizes[i], volume,
                    128, minSizes[i], Integer.MAX_VALUE, false);
        }
        int[] maxSizes = {1, 4, 25, 200};
        for (int i = 0; i < maxSizes.length; i++) {
            assertSameLabelling("maxSize=" + maxSizes[i], volume, 128, 0, maxSizes[i], false);
        }
    }

    @Test
    public void agreesAcrossTheThresholdSweep() {
        ImagePlus volume = gradient(24, 20, 5, 900);
        int[] thresholds = {1, 32, 64, 100, 128, 200, 254, 255};
        for (int i = 0; i < thresholds.length; i++) {
            assertSameLabelling("threshold=" + thresholds[i], volume,
                    thresholds[i], 0, Integer.MAX_VALUE, false);
        }
    }

    /**
     * Exclude-on-edges, kept separate and reported rather than merged into the
     * corpus above.
     *
     * <p>{@code Counter3D} marks edge contact against whichever provisional id a
     * voxel carries at the moment it is visited in its second pass, and
     * {@code replaceID} does not carry that flag across a later merge. So an
     * object whose only edge-touching part is labelled under an id that is
     * merged away afterwards can lose its flag and survive a filter it should
     * fail. {@link StreamingLabeller} ORs the flag into the component root, which
     * is the behaviour the option documents.
     *
     * <p>This test therefore records the disagreement rather than asserting one
     * away. If the corpus finds none, the two agree in practice and the
     * migration carries no risk here; if it finds some, the count and an example
     * are printed and the decision belongs in the CHANGELOG, not in a silently
     * relaxed assertion.
     */
    @Test
    public void reportsAnyExcludeOnEdgesDisagreement() {
        int compared = 0;
        int disagreed = 0;
        int referenceCrashed = 0;
        StringBuilder firstExample = new StringBuilder();

        for (int seed = 1; seed <= 24; seed++) {
            ImagePlus volume = seed % 2 == 0
                    ? blobs(24, 22, 6, 22, 3, 300 + seed)
                    : speckle(22, 20, 5, 0.18, 400 + seed);
            int[] actual = streamingLabels(volume, 128, 0, Integer.MAX_VALUE, true);
            int[] reference;
            try {
                reference = counter3dLabels(volume, 128, 0, Integer.MAX_VALUE, true);
            } catch (RuntimeException referenceDefect) {
                // See counter3dCrashesWhenTheLastVoxelStartsAnObject: the
                // reference implementation throws on a class of input it cannot
                // label at all, so there is nothing to compare against here.
                referenceCrashed++;
                continue;
            }
            compared++;
            if (!java.util.Arrays.equals(reference, actual)) {
                disagreed++;
                if (firstExample.length() == 0) {
                    firstExample.append("seed=").append(seed)
                            .append(" referenceObjects=").append(max(reference))
                            .append(" streamingObjects=").append(max(actual));
                }
            }
        }

        System.out.println("[oracle] excludeOnEdges: " + disagreed + "/" + compared
                + " comparable volumes disagree; " + referenceCrashed
                + " skipped because Counter3D threw"
                + (firstExample.length() == 0 ? "" : " - first disagreement: " + firstExample));
        assertTrue("corpus did not run", compared > 0);
    }

    /**
     * Targeted probe for the edge-flag propagation concern that the random
     * corpus above does not reach.
     *
     * <p>{@code Counter3D} records edge contact against whichever provisional id
     * a voxel carries when its second pass visits it, and {@code replaceID} does
     * not carry that flag across a later merge. The shape below is built so that
     * the object's only edge contact - the left column - is visited while it
     * still carries the second fragment's id, and the two fragments are not
     * bridged until several voxels later, at a position that touches no edge.
     *
     * <p>Printed rather than asserted in one direction: what matters is that the
     * migration knows, with evidence, whether "exclude objects on edges" is or
     * is not bit-identical between old and new.
     */
    @Test
    public void reportsEdgeFlagPropagationOnADeliberatelyAdversarialShape() {
        ImagePlus volume = fromAscii(new String[][] {
                {"........",
                 "....X...",
                 "....X...",
                 "X...X...",
                 "X...X...",
                 "XXXXX...",
                 "........"},
        });

        LabelResult unfiltered = StreamingLabeller.label(volume,
                new LabelParameters().threshold(128));
        assertEquals("fixture must be a single object touching the left edge",
                1, unfiltered.objectCount());

        int referenceObjects = max(counter3dLabels(volume, 128, 0, Integer.MAX_VALUE, true));
        int streamingObjects = StreamingLabeller.label(volume,
                new LabelParameters().threshold(128).excludeOnEdges(true)).objectCount();

        System.out.println("[oracle] excludeOnEdges adversarial shape: Counter3D kept "
                + referenceObjects + " object(s), StreamingLabeller kept " + streamingObjects
                + " (the object touches x=0, so the documented answer is 0)");

        assertEquals("StreamingLabeller must honour the documented rule",
                0, streamingObjects);
    }

    /**
     * A defect in the implementation being replaced, pinned so the migration can
     * describe it accurately rather than discover it from a user.
     *
     * <p>{@code Counter3D.findObjects()} sizes {@code IDcount} as
     * {@code new int[tag]}, where {@code tag} is bumped at the <i>start</i> of
     * the next voxel's iteration after a fresh label is consumed. If the final
     * voxel of the volume - x=width-1, y=height-1, last slice - is foreground
     * <i>and</i> has no foreground anterior neighbour, it consumes the highest
     * label and no further iteration follows, so {@code tag} equals that label
     * and the tally loop indexes one past the end.
     *
     * <p>The result is an {@code ArrayIndexOutOfBoundsException} out of the
     * plugin, not a message. The input is ordinary: a single object in the far
     * bottom-right corner of the last slice.
     *
     * <p>{@link StreamingLabeller} has no such array, and labels the same input.
     * This is a <b>fix</b>, not a regression, and belongs in the CHANGELOG.
     */
    @Test
    public void counter3dCrashesWhenTheLastVoxelStartsAnObject() {
        ImagePlus volume = fromAscii(new String[][] {
                {"X...",
                 "....",
                 "...."},
                {"....",
                 "....",
                 "...X"},
        });

        try {
            counter3dLabels(volume, 128, 0, Integer.MAX_VALUE, false);
            System.out.println("[oracle] Counter3D last-voxel defect NOT reproduced - "
                    + "re-check before relying on the CHANGELOG wording");
        } catch (ArrayIndexOutOfBoundsException expected) {
            System.out.println("[oracle] Counter3D last-voxel defect reproduced: "
                    + expected.getMessage());
        }

        LabelResult result = StreamingLabeller.label(volume, new LabelParameters().threshold(128));
        assertEquals("the replacement labels the input Counter3D cannot",
                2, result.objectCount());
        assertEquals(1L, result.voxelCount(1));
        assertEquals(1L, result.voxelCount(2));
    }

    // ──────────────────────────────────────────────────────────────────────

    private static void assertSameLabelling(String description,
                                            ImagePlus volume,
                                            int threshold,
                                            int minSize,
                                            int maxSize,
                                            boolean excludeOnEdges) {
        int[] reference = counter3dLabels(volume, threshold, minSize, maxSize, excludeOnEdges);
        int[] actual = streamingLabels(volume, threshold, minSize, maxSize, excludeOnEdges);

        assertEquals(description + ": array length", reference.length, actual.length);
        int width = volume.getWidth();
        int height = volume.getHeight();
        for (int i = 0; i < reference.length; i++) {
            if (reference[i] != actual[i]) {
                int z = i / (width * height);
                int remainder = i % (width * height);
                throw new AssertionError(description + ": label mismatch at x="
                        + (remainder % width) + ", y=" + (remainder / width) + ", z=" + z
                        + " (Counter3D=" + reference[i] + ", StreamingLabeller=" + actual[i]
                        + "); reference found " + max(reference) + " objects, streaming found "
                        + max(actual));
            }
        }
        assertEquals(description + ": object count", max(reference), max(actual));
    }

    private static int[] counter3dLabels(ImagePlus volume,
                                         int threshold,
                                         int minSize,
                                         int maxSize,
                                         boolean excludeOnEdges) {
        Counter3D counter = new Counter3D(volume.duplicate(), threshold,
                minSize, maxSize, excludeOnEdges, false);
        int[] objectIds = counter.getObjMapAsArray();
        return objectIds == null ? new int[0] : objectIds.clone();
    }

    private static int[] streamingLabels(ImagePlus volume,
                                         int threshold,
                                         int minSize,
                                         int maxSize,
                                         boolean excludeOnEdges) {
        LabelResult result = StreamingLabeller.label(volume, new LabelParameters()
                .threshold(threshold)
                .minSize(minSize)
                .maxSize(maxSize == Integer.MAX_VALUE ? Long.MAX_VALUE : maxSize)
                .excludeOnEdges(excludeOnEdges));

        ImageStack stack = result.labelImage().getStack();
        int width = result.labelImage().getWidth();
        int height = result.labelImage().getHeight();
        int[] flat = new int[width * height * stack.getSize()];
        int at = 0;
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int i = 0; i < width * height; i++) {
                flat[at++] = Math.round(processor.getf(i));
            }
        }
        return flat;
    }

    private static int max(int[] values) {
        int max = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > max) max = values[i];
        }
        return max;
    }

    private static ImagePlus fromAscii(String[][] slices) {
        int depth = slices.length;
        int height = slices[0].length;
        int width = slices[0][0].length();
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (slices[z][y].charAt(x) != '.') processor.set(x, y, 255);
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("ascii", stack);
    }

    private static ImagePlus speckle(int width, int height, int depth, double fraction, long seed) {
        Random random = new Random(seed);
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int i = 0; i < width * height; i++) {
                if (random.nextDouble() < fraction) processor.set(i, 255);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("speckle", stack);
    }

    private static ImagePlus blobs(int width, int height, int depth,
                                   int seedCount, int radius, long seed) {
        Random random = new Random(seed);
        byte[][] planes = new byte[depth][width * height];
        for (int s = 0; s < seedCount; s++) {
            int cx = random.nextInt(width);
            int cy = random.nextInt(height);
            int cz = random.nextInt(depth);
            int r = 1 + random.nextInt(Math.max(1, radius));
            for (int z = Math.max(0, cz - r); z <= Math.min(depth - 1, cz + r); z++) {
                for (int y = Math.max(0, cy - r); y <= Math.min(height - 1, cy + r); y++) {
                    for (int x = Math.max(0, cx - r); x <= Math.min(width - 1, cx + r); x++) {
                        planes[z][y * width + x] = (byte) 255;
                    }
                }
            }
        }
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ByteProcessor(width, height, planes[z], null));
        }
        return new ImagePlus("blobs", stack);
    }

    /** Smooth-ish intensity field, so a threshold sweep moves object boundaries gradually. */
    private static ImagePlus gradient(int width, int height, int depth, long seed) {
        Random random = new Random(seed);
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double wave = 128.0
                            + 90.0 * Math.sin(x * 0.7 + z * 0.4)
                            * Math.cos(y * 0.5 - z * 0.3);
                    int value = (int) Math.round(wave + random.nextInt(21) - 10);
                    processor.set(x, y, Math.max(0, Math.min(255, value)));
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("gradient", stack);
    }
}
