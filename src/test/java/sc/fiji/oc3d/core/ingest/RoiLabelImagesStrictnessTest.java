package sc.fiji.oc3d.core.ingest;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Line;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The ingest rules promoted from Volumetric Colocalization, which had them
 * while CPC did not.
 *
 * <p>Every case here previously produced a wrong answer rather than an error.
 * That is what makes them worth a test each: none of these failures is visible
 * in the output. A dropped ROI does not appear as a missing row, it appears as
 * one fewer object and a shifted denominator in every summary percentage; a
 * smeared ROI appears as an object several times its real volume.
 *
 * <p>The permissive {@link LabelUtils} is deliberately unchanged — it is called
 * from inside the core where inputs are already known good. These rules belong
 * to the front door.
 */
public class RoiLabelImagesStrictnessTest {

    private static ImagePlus reference(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) stack.addSlice(new ByteProcessor(width, height));
        ImagePlus image = new ImagePlus("reference", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static String rejectionFor(ImagePlus reference, Roi... rois) {
        try {
            RoiLabelImages.fromRois(reference, rois);
            fail("expected a rejection");
            return null;
        } catch (IllegalArgumentException expected) {
            return expected.getMessage();
        }
    }

    @Test
    public void aStraightLineIsRefusedBecauseItEnclosesNoVolume() {
        String message = rejectionFor(reference(10, 10, 1), new Line(1, 1, 8, 8));
        assertTrue(message, message.contains("must enclose an area"));
        assertTrue(message, message.contains("Line to Area"));
    }

    @Test
    public void aPolylineIsRefusedRatherThanFilledAsAPolygon() {
        // The dangerous one: a polyline's mask is the filled polygon of its
        // vertices, so accepting it invents a solid block that never existed.
        PolygonRoi polyline = new PolygonRoi(
                new int[]{1, 5, 9}, new int[]{1, 6, 1}, 3, Roi.POLYLINE);
        assertTrue(rejectionFor(reference(12, 12, 1), polyline)
                .contains("must enclose an area"));
    }

    @Test
    public void aPointSelectionIsRefusedRatherThanBecomingAOneVoxelObject() {
        String message = rejectionFor(reference(10, 10, 1), new PointRoi(4, 4));
        assertTrue(message, message.contains("point"));
        assertTrue(message, message.contains("must enclose an area"));
    }

    @Test
    public void anRoiEntirelyOutsideTheReferenceIsRefusedNotDropped() {
        String message = rejectionFor(reference(4, 4, 1), new Roi(50, 50, 5, 5));
        assertTrue(message, message.contains("entirely outside"));
        assertTrue(message, message.contains("4x4"));
    }

    @Test
    public void anRoiPositionedBeyondTheStackIsRefusedNotSmearedThroughIt() {
        // Without this rule the ROI misses the "draw on its own slice" branch
        // and falls into "draw on every slice", multiplying its volume by the
        // slice count.
        Roi roi = new Roi(1, 1, 2, 2);
        roi.setPosition(0, 9, 0);
        String message = rejectionFor(reference(6, 6, 3), roi);
        assertTrue(message, message.contains("slice 9"));
        assertTrue(message, message.contains("only 3"));
    }

    @Test
    public void aDegeneratePolygonIsRefused() {
        // Three collinear vertices. ImageJ reports bounds 4x0 and an all-zero
        // mask, so this enclosed nothing from the start. (A plain
        // Roi(x, y, 0, 0) is not the case to test — ImageJ normalises it to
        // 1x1, which does enclose a pixel.)
        PolygonRoi collinear = new PolygonRoi(
                new int[]{1, 3, 5}, new int[]{1, 1, 1}, 3, Roi.POLYGON);
        assertTrue(rejectionFor(reference(8, 8, 1), collinear)
                .contains("encloses no pixels"));
    }

    @Test
    public void anRoiWhoseShapeMissesTheImageIsRefusedEvenIfItsBoxDoesNot() {
        // The box spans x=0..3 and so overlaps a 2-wide image, but the
        // triangle's only mask pixels are at x=2 and x=3, both off the right
        // edge. Nothing is labelled, and without this rule the object simply
        // vanishes from every table.
        PolygonRoi triangle = new PolygonRoi(
                new int[]{0, 4, 4}, new int[]{0, 0, 1}, 3, Roi.POLYGON);
        String message = rejectionFor(reference(2, 6, 1), triangle);
        assertTrue(message, message.contains("encloses no pixels inside"));
        assertTrue(message, message.contains("2x6"));
    }

    @Test
    public void aNullRoiIsRefusedRatherThanLeavingAGapInTheLabelling() {
        String message = rejectionFor(reference(6, 6, 1), new Roi(1, 1, 2, 2), null);
        assertTrue(message, message.contains("ROI 2"));
        assertTrue(message, message.contains("null"));
    }

    @Test
    public void theOffendingRoiIsNamedWhenItHasAName() {
        // One stray ROI in a 500-entry manager set is otherwise unfindable.
        Roi roi = new Roi(50, 50, 5, 5);
        roi.setName("cell_17");
        assertTrue(rejectionFor(reference(4, 4, 1), roi).contains("ROI 1 (\"cell_17\")"));
    }

    @Test
    public void anRoiCompletelyCoveredByALaterOneIsStillAccepted() {
        // Overlapping ROIs resolve to the later label by design: a label image
        // cannot represent a voxel belonging to two objects. So an ROI hidden
        // entirely beneath a later one contributes nothing to the OUTPUT while
        // still enclosing plenty, and refusing it would refuse documented,
        // intended behaviour.
        //
        // This is why the encloses-nothing rule counts pixels from the ROI
        // itself rather than from the finished label image. Checking the
        // output broke this case, and every user with overlapping ROIs.
        Roi under = new Roi(1, 1, 3, 3);
        Roi over = new Roi(0, 0, 6, 6);
        ImagePlus labels = RoiLabelImages.fromRois(
                reference(8, 8, 1), new Roi[]{under, over});
        assertEquals("the later ROI wins where they overlap",
                2, (int) labels.getStack().getProcessor(1).getf(2, 2));
    }

    @Test
    public void manyIdenticalRoisStackedOnOnePixelAreAccepted() {
        // The degenerate form of the same thing: every ROI but the last is
        // completely overwritten.
        Roi[] rois = new Roi[64];
        for (int i = 0; i < rois.length; i++) rois[i] = new Roi(0, 0, 1, 1);
        ImagePlus labels = RoiLabelImages.fromRois(reference(1, 1, 1), rois);
        assertEquals(64.0, labels.getProcessor().getf(0), 0.0);
    }

    @Test
    public void validRoiSetsStillConvertUnchanged() {
        Roi first = new Roi(1, 1, 2, 2);
        Roi second = new Roi(5, 5, 2, 2);
        ImagePlus labels = RoiLabelImages.fromRois(
                reference(10, 10, 2), new Roi[]{first, second});
        assertEquals(2, labels.getStack().getSize());
        assertEquals(1, (int) labels.getStack().getProcessor(1).getf(2, 2));
        assertEquals(2, (int) labels.getStack().getProcessor(1).getf(6, 6));
        assertEquals(0, (int) labels.getStack().getProcessor(1).getf(9, 9));
    }

    @Test
    public void anRoiPositionedInsideTheStackIsStillDrawnOnItsSliceOnly() {
        Roi roi = new Roi(1, 1, 2, 2);
        roi.setPosition(0, 2, 0);
        ImagePlus labels = RoiLabelImages.fromRois(reference(6, 6, 3), new Roi[]{roi});
        assertEquals(0, (int) labels.getStack().getProcessor(1).getf(2, 2));
        assertEquals(1, (int) labels.getStack().getProcessor(2).getf(2, 2));
        assertEquals(0, (int) labels.getStack().getProcessor(3).getf(2, 2));
    }

    @Test
    public void anRoiWithoutAPositionIsStillAColumnThroughTheStack() {
        ImagePlus labels = RoiLabelImages.fromRois(
                reference(6, 6, 3), new Roi[]{new Roi(1, 1, 2, 2)});
        for (int z = 1; z <= 3; z++) {
            assertEquals("a 2D ROI is a column through the stack",
                    1, (int) labels.getStack().getProcessor(z).getf(2, 2));
        }
    }
}
