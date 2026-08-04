package sc.fiji.oc3d.core.label;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamingLabellerTest {

    @Test
    public void emptyVolumeProducesNoObjectsAndAnAllZeroLabelImage() {
        LabelResult result = StreamingLabeller.label(
                TestVolumes.blank(8, 8, 4, 8), new LabelParameters().threshold(1));

        assertEquals(0, result.objectCount());
        assertEquals(0L, result.totalObjectVoxels());
        assertEquals(4, result.labelImage().getStack().getSize());
        assertEquals(0, TestVolumes.labelsOf(result.labelImage())[2][3][3]);
    }

    @Test
    public void singleVoxelIsOneObject() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"...",
                 ".X.",
                 "..."},
        });
        LabelResult result = StreamingLabeller.label(volume, new LabelParameters().threshold(1));

        assertEquals(1, result.objectCount());
        assertEquals(1L, result.voxelCount(1));
        assertEquals(1, TestVolumes.labelsOf(result.labelImage())[0][1][1]);
    }

    @Test
    public void fullyForegroundVolumeIsOneObject() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"XXX", "XXX"},
                {"XXX", "XXX"},
        });
        LabelResult result = StreamingLabeller.label(volume, new LabelParameters().threshold(1));

        assertEquals(1, result.objectCount());
        assertEquals(12L, result.voxelCount(1));
        assertEquals(12L, result.totalObjectVoxels());
    }

    /**
     * Labels follow the z&rarr;y&rarr;x scan, so object 1 is whichever object's
     * first voxel is met first. Downstream tables, maps and macro filters index
     * on this; if the order changes, every published row moves.
     */
    @Test
    public void labelsAreNumberedByFirstAppearanceInScanOrder() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"..X",
                 "...",
                 "X.."},
        });
        int[][][] labels = TestVolumes.labelsOf(
                StreamingLabeller.label(volume, new LabelParameters().threshold(1)).labelImage());

        assertEquals("top-right voxel is scanned first", 1, labels[0][0][2]);
        assertEquals("bottom-left voxel is scanned second", 2, labels[0][2][0]);
    }

    /**
     * A U-shape whose arms are labelled separately before the bridge is reached
     * exercises the merge path: the surviving root must be the earlier arm's, so
     * the object still numbers as 1.
     */
    @Test
    public void armsMergedLateKeepTheEarlierRoot() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X.X",
                 "X.X",
                 "XXX"},
        });
        LabelResult result = StreamingLabeller.label(volume, new LabelParameters().threshold(1));
        int[][][] labels = TestVolumes.labelsOf(result.labelImage());

        assertEquals(1, result.objectCount());
        assertEquals(7L, result.voxelCount(1));
        assertEquals(1, labels[0][0][0]);
        assertEquals(1, labels[0][0][2]);
    }

    @Test
    public void thresholdIsInclusiveOfItsOwnValue() {
        ImagePlus volume = TestVolumes.fromValues(new int[][][] {
                {{9, 10, 11}},
        });

        int[][][] atTen = TestVolumes.labelsOf(StreamingLabeller.label(
                volume, new LabelParameters().threshold(10)).labelImage());
        assertEquals("value 9 is below threshold", 0, atTen[0][0][0]);
        assertEquals("value 10 is foreground: the rule is value >= threshold", 1, atTen[0][0][1]);
        assertEquals(1, atTen[0][0][2]);

        int[][][] atEleven = TestVolumes.labelsOf(StreamingLabeller.label(
                volume, new LabelParameters().threshold(11)).labelImage());
        assertEquals(0, atEleven[0][0][0]);
        assertEquals(0, atEleven[0][0][1]);
        assertEquals(1, atEleven[0][0][2]);
    }

    @Test
    public void sizeFiltersEraseObjectsAndRenumberTheSurvivors() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X.XX.XXX"},
        });

        LabelResult unfiltered = StreamingLabeller.label(volume, new LabelParameters().threshold(1));
        assertEquals(3, unfiltered.objectCount());

        LabelResult minTwo = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).minSize(2));
        assertEquals(2, minTwo.objectCount());
        assertEquals(2L, minTwo.voxelCount(1));
        assertEquals(3L, minTwo.voxelCount(2));

        int[][][] labels = TestVolumes.labelsOf(minTwo.labelImage());
        assertEquals("filtered object becomes background, not label 0-with-a-gap", 0, labels[0][0][0]);
        assertEquals(1, labels[0][0][2]);
        assertEquals(2, labels[0][0][5]);

        LabelResult maxTwo = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).maxSize(2));
        assertEquals(2, maxTwo.objectCount());
        assertEquals(1L, maxTwo.voxelCount(1));
        assertEquals(2L, maxTwo.voxelCount(2));
    }

    @Test
    public void excludeOnEdgesDropsObjectsTouchingAnyLateralBorder() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {"X....",
                 ".....",
                 "..X..",
                 ".....",
                 "....X"},
        });

        assertEquals(3, StreamingLabeller.label(volume,
                new LabelParameters().threshold(1)).objectCount());

        LabelResult excluded = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).excludeOnEdges(true));
        assertEquals(1, excluded.objectCount());
        assertEquals(1, TestVolumes.labelsOf(excluded.labelImage())[0][2][2]);
    }

    /**
     * A single-slice stack has no z edge. {@code Counter3D.findObjects()} guards
     * its z test with {@code nbSlices > 1}, so a 2D image is not entirely
     * edge-touching, and a plugin run on one slice does not silently return zero
     * objects when the user ticks "exclude on edges".
     */
    @Test
    public void singleSliceStackHasNoZEdge() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {".....",
                 ".....",
                 "..X..",
                 ".....",
                 "....."},
        });
        assertEquals(1, StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).excludeOnEdges(true)).objectCount());
    }

    @Test
    public void multiSliceStackTreatsFirstAndLastSliceAsEdges() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {
                {".....", ".....", "..X..", ".....", "....."},
                {".....", ".....", ".....", ".....", "....."},
                {".....", ".....", "..X..", ".....", "....."},
        });
        assertEquals(2, StreamingLabeller.label(volume,
                new LabelParameters().threshold(1)).objectCount());
        assertEquals(0, StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).excludeOnEdges(true)).objectCount());
    }

    @Test
    public void outputBitDepthFollowsObjectCountNotSourceDepth() {
        assertEquals("8-bit up to 255 objects",
                8, checkerLabelImage(255).getBitDepth());
        assertEquals("16-bit past 255 objects",
                16, checkerLabelImage(256).getBitDepth());
    }

    @Test
    public void thirtyTwoBitSourcesAreLabelledRatherThanRejected() {
        ImageStack stack = new ImageStack(4, 1);
        FloatProcessor processor = new FloatProcessor(4, 1);
        processor.setf(0, 5.5f);
        processor.setf(1, 5.5f);
        processor.setf(2, 0f);
        processor.setf(3, 9.0f);
        stack.addSlice(processor);

        LabelResult result = StreamingLabeller.label(new ImagePlus("float", stack),
                new LabelParameters().threshold(1.0));

        assertEquals("32-bit input is exactly what the mcib3d path was needed for",
                2, result.objectCount());
        assertEquals(2L, result.voxelCount(1));
        assertEquals(1L, result.voxelCount(2));
    }

    @Test
    public void sixteenBitSourcesUseUnsignedValues() {
        ImageStack stack = new ImageStack(3, 1);
        ShortProcessor processor = new ShortProcessor(3, 1);
        processor.set(0, 40000);
        processor.set(1, 0);
        processor.set(2, 65535);
        stack.addSlice(processor);

        LabelResult result = StreamingLabeller.label(new ImagePlus("short", stack),
                new LabelParameters().threshold(30000));
        assertEquals(2, result.objectCount());
    }

    @Test
    public void calibrationIsCopiedNotShared() {
        ImagePlus volume = TestVolumes.fromAscii(new String[][] {{"X"}});
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.25;
        calibration.pixelHeight = 0.25;
        calibration.pixelDepth = 1.5;
        calibration.setUnit("micron");
        volume.setCalibration(calibration);

        ImagePlus labels = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1)).labelImage();

        assertEquals(0.25, labels.getCalibration().pixelWidth, 0.0);
        assertEquals(1.5, labels.getCalibration().pixelDepth, 0.0);
        assertEquals("micron", labels.getCalibration().getUnit());

        labels.getCalibration().pixelWidth = 99.0;
        assertEquals("mutating the result must not reach back into the source",
                0.25, volume.getCalibration().pixelWidth, 0.0);
    }

    @Test
    public void hyperstacksAreRejectedWithAnExplanation() {
        ImageStack stack = new ImageStack(4, 4);
        for (int i = 0; i < 6; i++) {
            stack.addSlice(new ByteProcessor(4, 4));
        }
        ImagePlus hyperstack = new ImagePlus("hyper", stack);
        hyperstack.setDimensions(2, 3, 1);

        try {
            StreamingLabeller.label(hyperstack, new LabelParameters());
            fail("expected an IllegalArgumentException for a multi-channel input");
        } catch (IllegalArgumentException expected) {
            assertTrue("message must say what was wrong, not just that something was: "
                            + expected.getMessage(),
                    expected.getMessage().contains("channels=2"));
            assertTrue(expected.getMessage().contains("multiply every volume"));
        }
    }

    @Test
    public void rgbInputIsRejected() {
        ImageStack stack = new ImageStack(4, 4);
        stack.addSlice(new ColorProcessor(4, 4));
        try {
            StreamingLabeller.label(new ImagePlus("rgb", stack), new LabelParameters());
            fail("expected an IllegalArgumentException for an RGB input");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bitDepth=24"));
        }
    }

    @Test
    public void aHyperstackVolumeCanBeExtractedAndThenLabelled() {
        // ImageJ hyperstacks are stored in czt order, channels varying fastest.
        ImageStack stack = new ImageStack(3, 1);
        for (int z = 1; z <= 2; z++) {
            for (int channel = 1; channel <= 2; channel++) {
                ByteProcessor processor = new ByteProcessor(3, 1);
                processor.set(channel == 1 ? 0 : 2, 255);
                stack.addSlice(processor);
            }
        }
        ImagePlus hyperstack = new ImagePlus("hyper", stack);
        hyperstack.setDimensions(2, 2, 1);

        LabelResult channelOne = StreamingLabeller.label(
                LabelImages.volumeOf(hyperstack, 1, 1), new LabelParameters().threshold(1));
        LabelResult channelTwo = StreamingLabeller.label(
                LabelImages.volumeOf(hyperstack, 2, 1), new LabelParameters().threshold(1));

        assertEquals(1, channelOne.objectCount());
        assertEquals(2L, channelOne.voxelCount(1));
        assertEquals(1, channelTwo.objectCount());
        assertEquals(2L, channelTwo.voxelCount(1));
    }

    @Test
    public void voxelCountOutsideTheLabelRangeIsZeroRatherThanAnException() {
        LabelResult result = StreamingLabeller.label(
                TestVolumes.fromAscii(new String[][] {{"X"}}), new LabelParameters().threshold(1));
        assertEquals(0L, result.voxelCount(0));
        assertEquals(0L, result.voxelCount(2));
        assertEquals(0L, result.voxelCount(-1));
    }

    @Test
    public void progressIsReportedForEverySliceOfBothPasses() {
        final int[] calls = new int[1];
        StreamingLabeller.label(TestVolumes.blank(4, 4, 5, 8), new LabelParameters(),
                new ProgressCounter(calls));
        assertEquals("one report per slice per pass", 10, calls[0]);
    }

    /**
     * Six-connectivity on speckle produces far more objects than 26. This is a
     * sanity net for the rule wiring rather than a numeric contract - the exact
     * counts belong to {@link ConnectivityDiscriminatorTest}.
     */
    @Test
    public void sixConnectivityFindsMoreObjectsThanTwentySixOnSpeckle() {
        ImagePlus volume = TestVolumes.speckle(24, 24, 6, 0.2, 20260803L);
        int twentySix = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).connectivity(Connectivity.TWENTY_SIX)).objectCount();
        int six = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).connectivity(Connectivity.SIX)).objectCount();

        assertTrue("26-connectivity must not find more objects than 6 (26=" + twentySix
                + ", 6=" + six + ")", six >= twentySix);
        assertTrue(twentySix > 0);
    }

    /** Every foreground voxel must carry a label, and no background voxel may. */
    @Test
    public void everyForegroundVoxelIsLabelledAndNoBackgroundVoxelIs() {
        ImagePlus volume = TestVolumes.blobs(32, 32, 8, 40, 3, 7L);
        LabelResult result = StreamingLabeller.label(volume, new LabelParameters().threshold(1));

        int[][][] labels = TestVolumes.labelsOf(result.labelImage());
        ImageStack source = volume.getStack();
        long labelled = 0L;
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    boolean foreground = source.getProcessor(z + 1).getf(y * 32 + x) >= 1f;
                    int label = labels[z][y][x];
                    if (foreground) {
                        assertTrue("unlabelled foreground voxel at " + x + "," + y + "," + z,
                                label > 0);
                        labelled++;
                    } else {
                        assertEquals("labelled background voxel at " + x + "," + y + "," + z,
                                0, label);
                    }
                    assertTrue(label <= result.objectCount());
                }
            }
        }
        assertEquals(labelled, result.totalObjectVoxels());
    }

    /** Labels must be dense: every value in 1..objectCount actually appears. */
    @Test
    public void labelsAreDenseWithNoGaps() {
        ImagePlus volume = TestVolumes.blobs(24, 24, 6, 30, 2, 11L);
        LabelResult result = StreamingLabeller.label(volume,
                new LabelParameters().threshold(1).minSize(3));

        boolean[] seen = new boolean[result.objectCount() + 1];
        int[][][] labels = TestVolumes.labelsOf(result.labelImage());
        for (int z = 0; z < labels.length; z++) {
            for (int y = 0; y < labels[z].length; y++) {
                for (int x = 0; x < labels[z][y].length; x++) {
                    int label = labels[z][y][x];
                    if (label > 0) seen[label] = true;
                }
            }
        }
        for (int label = 1; label <= result.objectCount(); label++) {
            assertTrue("label " + label + " of " + result.objectCount() + " never appears",
                    seen[label]);
        }
    }

    /** Two runs over the same input must agree exactly; a flaky labeller certifies nothing. */
    @Test
    public void labellingIsDeterministic() {
        ImagePlus volume = TestVolumes.blobs(24, 24, 6, 30, 2, 13L);
        LabelParameters parameters = new LabelParameters().threshold(1);

        int[][][] first = TestVolumes.labelsOf(
                StreamingLabeller.label(volume, parameters).labelImage());
        int[][][] second = TestVolumes.labelsOf(
                StreamingLabeller.label(volume, parameters).labelImage());

        for (int z = 0; z < first.length; z++) {
            for (int y = 0; y < first[z].length; y++) {
                for (int x = 0; x < first[z][y].length; x++) {
                    assertEquals(first[z][y][x], second[z][y][x]);
                }
            }
        }
    }

    @Test
    public void nullSourceIsRejected() {
        try {
            StreamingLabeller.label(null, new LabelParameters());
            fail("expected an IllegalArgumentException for a null source");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static ImagePlus checkerLabelImage(int objects) {
        // Isolated voxels two apart in x are separate objects under both rules.
        int width = objects * 2;
        ImageStack stack = new ImageStack(width, 1);
        ByteProcessor processor = new ByteProcessor(width, 1);
        for (int i = 0; i < objects; i++) {
            processor.set(i * 2, 255);
        }
        stack.addSlice(processor);

        LabelResult result = StreamingLabeller.label(new ImagePlus("ladder", stack),
                new LabelParameters().threshold(1));
        assertEquals(objects, result.objectCount());
        return result.labelImage();
    }

    private static final class ProgressCounter implements sc.fiji.oc3d.core.progress.ProgressListener {
        private final int[] calls;

        ProgressCounter(int[] calls) {
            this.calls = calls;
        }

        @Override
        public void progress(String stage, long done, long total) {
            calls[0]++;
        }
    }
}
