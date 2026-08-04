package sc.fiji.oc3d.core.label;

import ij.ImagePlus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The four fixtures that tell 6- from 26-connectivity apart, and nothing else.
 *
 * <p>These are the highest-value assertions in the module. Connectivity is the
 * one decision that, if wrong, changes <b>every object count in every output</b>
 * at once - a whole-corpus failure rather than a drifting column. Pinning it in
 * a test that fails loudly is cheaper than discovering it from a user's numbers.
 *
 * <p>The pinned value is 26, established from the shipped bytecode of both
 * implementations being replaced - see {@link Connectivity} for the evidence.
 */
public class ConnectivityDiscriminatorTest {

    /** Two voxels sharing a face: one object under either rule. */
    @Test
    public void faceSharingVoxelsAreOneObjectUnderBothRules() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"XX."},
        });
        assertEquals(1, count(volume, Connectivity.TWENTY_SIX));
        assertEquals(1, count(volume, Connectivity.SIX));
    }

    /** Two voxels sharing only an edge: one object under 26, two under 6. */
    @Test
    public void edgeSharingVoxelsSplitUnderSixConnectivity() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X..",
                 ".X."},
        });
        assertEquals(1, count(volume, Connectivity.TWENTY_SIX));
        assertEquals(2, count(volume, Connectivity.SIX));
    }

    /** Two voxels sharing only a corner across slices: one object under 26, two under 6. */
    @Test
    public void cornerSharingVoxelsSplitUnderSixConnectivity() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X..",
                 "...",
                 "..."},
                {"...",
                 ".X.",
                 "..."},
        });
        assertEquals(1, count(volume, Connectivity.TWENTY_SIX));
        assertEquals(2, count(volume, Connectivity.SIX));
    }

    /** A voxel chain stepping diagonally through z: one object under 26, four under 6. */
    @Test
    public void diagonalChainThroughZSplitsUnderSixConnectivity() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X...",
                 "....",
                 "....",
                 "...."},
                {"....",
                 ".X..",
                 "....",
                 "...."},
                {"....",
                 "....",
                 "..X.",
                 "...."},
                {"....",
                 "....",
                 "....",
                 "...X"},
        });
        assertEquals(1, count(volume, Connectivity.TWENTY_SIX));
        assertEquals(4, count(volume, Connectivity.SIX));
    }

    /** The default must be 26. Changing it silently would change every published number. */
    @Test
    public void defaultConnectivityIsTwentySix() {
        assertEquals(Connectivity.TWENTY_SIX, new LabelParameters().connectivity());

        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X..",
                 ".X."},
        });
        LabelResult result = StreamingLabeller.label(volume, new LabelParameters().threshold(1));
        assertEquals(1, result.objectCount());
        assertEquals(Connectivity.TWENTY_SIX, result.connectivity());
    }

    private static int count(ImagePlus volume, Connectivity connectivity) {
        return StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).connectivity(connectivity)).objectCount();
    }
}
