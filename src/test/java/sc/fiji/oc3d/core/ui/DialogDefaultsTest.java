package sc.fiji.oc3d.core.ui;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import org.junit.Test;

import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DialogDefaultsTest {

    @Test
    public void centreSliceRoundsUpForOddAndEvenDepths() {
        assertEquals(1, DialogDefaults.centerSlice(null));
        assertEquals(1, DialogDefaults.centerSlice(Fixtures.blank("i", 4, 4, 1, 8)));
        assertEquals(3, DialogDefaults.centerSlice(stackOfDepth(5)));
        assertEquals(3, DialogDefaults.centerSlice(stackOfDepth(6)));
        assertEquals(5, DialogDefaults.centerSlice(stackOfDepth(9)));
    }

    @Test
    public void movingToTheCentreSliceKeepsChannelAndFrame() {
        ImagePlus image = Fixtures.blank("hyper", 4, 4, 12, 8);
        image.setDimensions(2, 3, 2);
        image.setPosition(2, 1, 2);

        DialogDefaults.moveToCenterSlice(image);

        assertEquals(2, image.getC());
        assertEquals(2, image.getZ());
        assertEquals(2, image.getT());
    }

    @Test
    public void movingANullImageIsANoOp() {
        DialogDefaults.moveToCenterSlice(null);
    }

    @Test
    public void rangeIgnoresNonFiniteVoxels() {
        ImagePlus image = Fixtures.blank("i", 4, 4, 1, 32);
        ImageProcessor slice = image.getStack().getProcessor(1);
        slice.setf(0, 0, Float.NaN);
        slice.setf(1, 0, Float.NEGATIVE_INFINITY);
        slice.setf(2, 0, -5f);
        slice.setf(3, 0, 25f);

        assertEquals("a NaN must not make the slider range NaN",
                -5.0, DialogDefaults.finiteMinimum(image, 0), 1e-6);
        assertEquals(25.0, DialogDefaults.finiteMaximum(image, 0), 1e-6);
    }

    @Test
    public void anAllNonFiniteImageFallsBackToTheSuppliedDefault() {
        ImagePlus image = Fixtures.blank("i", 2, 2, 1, 32);
        ImageProcessor slice = image.getStack().getProcessor(1);
        for (int i = 0; i < slice.getPixelCount(); i++) {
            slice.setf(i, Float.NaN);
        }

        assertEquals(7.0, DialogDefaults.finiteMaximum(image, 7.0), 0.0);
        assertEquals(3.0, DialogDefaults.finiteMinimum(image, 3.0), 0.0);
        assertEquals(0.0, DialogDefaults.finiteMaximum(null, 0.0), 0.0);
    }

    @Test
    public void sliderBoundsBracketTheData() {
        ImagePlus image = Fixtures.blank("i", 4, 4, 1, 16);
        image.getStack().getProcessor(1).setf(0, 0, 12);
        image.getStack().getProcessor(1).setf(1, 0, 900);

        assertEquals(0, DialogDefaults.sliderMinimum(image));
        assertEquals(900, DialogDefaults.sliderMaximum(image, 100));
    }

    @Test
    public void theSliderAlwaysReachesTheCurrentThreshold() {
        ImagePlus image = Fixtures.blank("i", 4, 4, 1, 8);
        image.getStack().getProcessor(1).setf(0, 0, 3);

        assertEquals("a slider that cannot reach its own value is unusable",
                50, DialogDefaults.sliderMaximum(image, 50));
    }

    @Test
    public void isoDataThresholdComesFromTheCentreSliceAndFallsBackCleanly() {
        ImagePlus image = Fixtures.blank("i", 8, 8, 3, 8);
        // Empty first slice, bimodal middle slice: the centre is what gets used.
        ImageProcessor middle = image.getStack().getProcessor(2);
        for (int i = 0; i < middle.getPixelCount() / 2; i++) {
            middle.setf(i, 200);
        }

        int threshold = DialogDefaults.isoDataThresholdAtCenterSlice(image, -1);

        assertTrue("expected a threshold inside the data range, got " + threshold,
                threshold > 0 && threshold < 200);
        assertEquals(-1, DialogDefaults.isoDataThresholdAtCenterSlice(null, -1));
    }

    private static ImagePlus stackOfDepth(int depth) {
        ImagePlus image = Fixtures.blank("i", 4, 4, depth, 8);
        image.setDimensions(1, depth, 1);
        return image;
    }
}
