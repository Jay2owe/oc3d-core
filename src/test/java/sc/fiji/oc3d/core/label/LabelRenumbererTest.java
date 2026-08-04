package sc.fiji.oc3d.core.label;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LabelRenumbererTest {

    @Test
    public void gappedLabelsBecomeDenseOneToN() {
        ImagePlus labels = Fixtures.blank("labels", 6, 6, 1, 16);
        ImageProcessor slice = labels.getStack().getProcessor(1);
        slice.setf(0, 0, 40);
        slice.setf(1, 0, 7);
        slice.setf(2, 0, 900);

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        assertEquals(3, result.objectCount());
        Set<Integer> present = labelsPresent(labels);
        assertTrue(present.contains(Integer.valueOf(1)));
        assertTrue(present.contains(Integer.valueOf(2)));
        assertTrue(present.contains(Integer.valueOf(3)));
        assertEquals(3, present.size());
    }

    @Test
    public void orderIsFirstSliceThenCentroidYThenCentroidX() {
        ImagePlus labels = Fixtures.blank("labels", 8, 8, 3, 16);
        // Slice 3 (last), so it must come last however low its id.
        labels.getStack().getProcessor(3).setf(0, 0, 5);
        // Slice 1, y=4 - lower row, so second.
        labels.getStack().getProcessor(1).setf(1, 4, 60);
        // Slice 1, y=1 - top row, so first.
        labels.getStack().getProcessor(1).setf(6, 1, 99);

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        assertEquals(Integer.valueOf(1), result.oldToNew().get(Integer.valueOf(99)));
        assertEquals(Integer.valueOf(2), result.oldToNew().get(Integer.valueOf(60)));
        assertEquals(Integer.valueOf(3), result.oldToNew().get(Integer.valueOf(5)));
    }

    @Test
    public void sameSliceAndRowFallsBackToCentroidX() {
        ImagePlus labels = Fixtures.blank("labels", 8, 8, 1, 16);
        labels.getStack().getProcessor(1).setf(6, 2, 11);
        labels.getStack().getProcessor(1).setf(1, 2, 22);

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        assertEquals(Integer.valueOf(1), result.oldToNew().get(Integer.valueOf(22)));
        assertEquals(Integer.valueOf(2), result.oldToNew().get(Integer.valueOf(11)));
    }

    @Test
    public void theMappingRoundTripsBothWays() {
        ImagePlus labels = Fixtures.blank("labels", 6, 6, 1, 16);
        labels.getStack().getProcessor(1).setf(0, 0, 12);
        labels.getStack().getProcessor(1).setf(3, 3, 34);

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        for (Integer oldLabel : result.oldToNew().keySet()) {
            Integer newLabel = result.oldToNew().get(oldLabel);
            assertEquals("the original id must be recoverable for tracing",
                    oldLabel, result.newToOld().get(newLabel));
        }
    }

    @Test
    public void labelsOutsideTheKeepSetAreErased() {
        ImagePlus labels = Fixtures.blank("labels", 6, 6, 1, 16);
        ImageProcessor slice = labels.getStack().getProcessor(1);
        slice.setf(0, 0, 1);
        slice.setf(1, 0, 2);
        slice.setf(2, 0, 3);

        Set<Integer> keep = new HashSet<Integer>();
        keep.add(Integer.valueOf(1));
        keep.add(Integer.valueOf(3));

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels, keep);

        assertEquals(2, result.objectCount());
        assertEquals(1, (int) slice.getf(0, 0));
        assertEquals("a filtered-out object becomes background", 0, (int) slice.getf(1, 0));
        assertEquals("survivors are renumbered densely", 2, (int) slice.getf(2, 0));
    }

    @Test
    public void anEmptyKeepSetErasesEverything() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 16);
        labels.getStack().getProcessor(1).setf(0, 0, 1);

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels, new HashSet<Integer>());

        assertEquals(0, result.objectCount());
        assertTrue(labelsPresent(labels).isEmpty());
    }

    @Test
    public void nonIntegralPixelsAreTreatedAsBackground() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 32);
        ImageProcessor slice = labels.getStack().getProcessor(1);
        slice.setf(0, 0, 2.5f);
        slice.setf(1, 0, Float.NaN);
        slice.setf(2, 0, -3f);
        slice.setf(3, 0, 4f);

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        assertEquals("only the one genuine integer label survives", 1, result.objectCount());
        assertEquals(1, (int) slice.getf(3, 0));
        assertEquals(0, (int) slice.getf(0, 0));
    }

    @Test
    public void anImageWithNoLabelsGivesAnEmptyResult() {
        LabelRenumberer.Result result =
                LabelRenumberer.renumber(Fixtures.blank("labels", 4, 4, 2, 8));
        assertEquals(0, result.objectCount());
        assertTrue(result.oldToNew().isEmpty());
    }

    @Test
    public void aNullImageIsNotAnError() {
        LabelRenumberer.Result result = LabelRenumberer.renumber(null);
        assertEquals("a run that detected nothing is not a failure", 0, result.objectCount());
        assertTrue(result.newToOld().isEmpty());
    }

    @Test
    public void theReturnedMapsAreUnmodifiable() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 8);
        labels.getStack().getProcessor(1).setf(0, 0, 1);
        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);
        try {
            result.oldToNew().put(Integer.valueOf(9), Integer.valueOf(9));
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, result.oldToNew().size());
        }
    }

    private static Set<Integer> labelsPresent(ImagePlus image) {
        Set<Integer> present = new HashSet<Integer>();
        for (int z = 1; z <= image.getStackSize(); z++) {
            ImageProcessor processor = image.getStack().getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = (int) processor.getf(i);
                if (label > 0) present.add(Integer.valueOf(label));
            }
        }
        return present;
    }
}
