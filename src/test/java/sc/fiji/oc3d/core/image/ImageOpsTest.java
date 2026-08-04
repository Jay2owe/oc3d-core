package sc.fiji.oc3d.core.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.Test;

import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ImageOpsTest {

    @Test
    public void duplicateCopiesPixelsAndCalibrationWithoutSharing() {
        ImagePlus source = Fixtures.calibrate(
                Fixtures.cube("source", 6, 1, 1, 1, 2, 7), 0.5, 0.5, 2.0, "mm");

        ImagePlus copy = ImageOps.duplicateThreadSafe(source);

        assertNotSame(source.getStack().getProcessor(2), copy.getStack().getProcessor(2));
        assertEquals(7, (int) copy.getStack().getProcessor(2).getf(1, 1));
        assertEquals(0.5, copy.getCalibration().pixelWidth, 0.0);
        assertEquals(2.0, copy.getCalibration().pixelDepth, 0.0);

        copy.getStack().getProcessor(2).setf(1, 1, 99);
        assertEquals("the copy must own its pixels",
                7, (int) source.getStack().getProcessor(2).getf(1, 1));
    }

    @Test
    public void nullInputsReturnNull() {
        assertNull(ImageOps.duplicateThreadSafe(null));
        assertNull(ImageOps.processingSnapshot(null));
        assertNull(ImageOps.thresholdBinaryMaskCopy(null, 1));
        assertNull(ImageOps.thresholdRetainedCurrentPlaneCopy(null, 1));
    }

    @Test
    public void hyperstackDimensionsSurviveDuplication() {
        ImagePlus source = Fixtures.blank("hyper", 4, 4, 8, 8);
        source.setDimensions(2, 2, 2);
        source.setOpenAsHyperStack(true);

        ImagePlus copy = ImageOps.duplicateThreadSafe(source);

        assertEquals(2, copy.getNChannels());
        assertEquals(2, copy.getNSlices());
        assertEquals(2, copy.getNFrames());
        assertTrue(copy.isHyperStack());
    }

    @Test
    public void aSubRangeCopiesOnlyTheRequestedPlanes() {
        ImagePlus source = Fixtures.blank("source", 4, 4, 4, 8);
        for (int z = 1; z <= 4; z++) {
            source.getStack().getProcessor(z).setf(0, 0, z);
        }

        ImagePlus copy = ImageOps.duplicateThreadSafe(source, 1, 1, 2, 3, 1, 1);

        assertEquals(2, copy.getStackSize());
        assertEquals(2, (int) copy.getStack().getProcessor(1).getf(0, 0));
        assertEquals(3, (int) copy.getStack().getProcessor(2).getf(0, 0));
    }

    @Test
    public void anImageBuiltStraightFromAStackStillIndexesCorrectly() {
        // ImagePlus.getStackIndex() does not call verifyDimensions(), so on an
        // image constructed from an ImageStack and never displayed, nSlices is
        // still 1 and every slice request would silently clamp to slice 1.
        ImagePlus source = Fixtures.blank("never displayed", 4, 4, 3, 8);
        for (int z = 1; z <= 3; z++) {
            source.getStack().getProcessor(z).setf(0, 0, z * 10);
        }

        ImagePlus copy = ImageOps.duplicateThreadSafe(source, 1, 1, 3, 3, 1, 1);

        assertEquals(1, copy.getStackSize());
        assertEquals("slice 3 must be copied, not slice 1",
                30, (int) copy.getStack().getProcessor(1).getf(0, 0));
    }

    @Test
    public void retainedThresholdKeepsIntensitiesAtOrAboveTheCutoff() {
        ImagePlus source = Fixtures.blank("source", 4, 4, 1, 8);
        ImageProcessor slice = source.getStack().getProcessor(1);
        slice.setf(0, 0, 39);
        slice.setf(1, 0, 40);
        slice.setf(2, 0, 200);

        ImagePlus thresholded = ImageOps.thresholdRetainedIntensityCopy(source, 40);
        ImageProcessor out = thresholded.getStack().getProcessor(1);

        assertEquals("value >= threshold is foreground", 0, (int) out.getf(0, 0));
        assertEquals(40, (int) out.getf(1, 0));
        assertEquals("intensities are kept, not binarised", 200, (int) out.getf(2, 0));
    }

    @Test
    public void nonFiniteVoxelsAreZeroedByTheThreshold() {
        ImagePlus source = Fixtures.blank("source", 4, 4, 1, 32);
        ImageProcessor slice = source.getStack().getProcessor(1);
        slice.setf(0, 0, Float.NaN);
        slice.setf(1, 0, Float.POSITIVE_INFINITY);
        slice.setf(2, 0, 50f);

        ImageProcessor retained = ImageOps.thresholdRetainedIntensityCopy(source, 10)
                .getStack().getProcessor(1);
        assertEquals("NaN must not join two objects", 0f, retained.getf(0, 0), 0f);
        assertEquals(0f, retained.getf(1, 0), 0f);
        assertEquals(50f, retained.getf(2, 0), 0f);

        ImageProcessor mask = ImageOps.thresholdBinaryMaskCopy(source, 10)
                .getStack().getProcessor(1);
        assertEquals(0, (int) mask.getf(0, 0));
        assertEquals(0, (int) mask.getf(1, 0));
        assertEquals(255, (int) mask.getf(2, 0));
    }

    @Test
    public void binaryMaskIsEightBitAndUses255ForForeground() {
        ImagePlus source = Fixtures.blank("source", 4, 4, 2, 16);
        source.getStack().getProcessor(1).setf(0, 0, 1000);
        source.getStack().getProcessor(2).setf(1, 1, 5);

        ImagePlus mask = ImageOps.thresholdBinaryMaskCopy(source, 100);

        assertTrue(mask.getStack().getProcessor(1) instanceof ByteProcessor);
        assertEquals(255, (int) mask.getStack().getProcessor(1).getf(0, 0));
        assertEquals(0, (int) mask.getStack().getProcessor(2).getf(1, 1));
    }

    @Test
    public void thresholdCopiesLeaveTheSourceUntouched() {
        ImagePlus source = Fixtures.blank("source", 4, 4, 1, 8);
        source.getStack().getProcessor(1).setf(0, 0, 5);

        ImageOps.thresholdRetainedIntensityCopy(source, 100);
        ImageOps.thresholdBinaryMaskCopy(source, 100);

        assertEquals(5, (int) source.getStack().getProcessor(1).getf(0, 0));
    }

    @Test
    public void aSinglePlaneImageFallsBackToTheCurrentPlane() {
        ImagePlus source = new ImagePlus("plane", new ByteProcessor(4, 4));
        source.getProcessor().setf(1, 1, 90);

        ImagePlus copy = ImageOps.thresholdRetainedCurrentPlaneCopy(source, 50);

        assertEquals(1, copy.getStackSize());
        assertEquals(90, (int) copy.getProcessor().getf(1, 1));
        assertEquals(0, (int) copy.getProcessor().getf(0, 0));
    }

    @Test
    public void sliceLabelsSurviveDuplication() {
        ImageStack stack = new ImageStack(4, 4);
        stack.addSlice("first", new ByteProcessor(4, 4));
        stack.addSlice("second", new ByteProcessor(4, 4));
        ImagePlus source = new ImagePlus("labelled", stack);

        ImagePlus copy = ImageOps.duplicateThreadSafe(source);

        assertEquals("first", copy.getStack().getSliceLabel(1));
        assertEquals("second", copy.getStack().getSliceLabel(2));
    }
}
