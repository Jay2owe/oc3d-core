package sc.fiji.oc3d.core.map;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.Test;

import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObjectMapBuilderTest {

    @Test
    public void objectMapCopiesAndLeavesTheSourceAlone() {
        ImagePlus labels = Fixtures.cube("source", 6, 1, 1, 1, 2, 3);

        ImagePlus map = ObjectMapBuilder.objectMap(labels, statsFor(3, 2, 2, 2), "source");

        assertNotNull(map);
        assertEquals("Objects map of source", map.getTitle());
        assertEquals("source", labels.getTitle());
        assertEquals(3, (int) map.getStack().getProcessor(2).getf(1, 1));
    }

    @Test
    public void objectMapInPlaceReusesTheLabelImage() {
        ImagePlus labels = Fixtures.cube("source", 6, 1, 1, 1, 2, 1);

        ImagePlus map = ObjectMapBuilder.objectMapInPlace(labels, statsFor(1, 1, 1, 1), "source");

        assertSame("no second full-volume allocation", labels, map);
        assertEquals("Objects map of source", map.getTitle());
    }

    @Test
    public void nullOrStacklessInputYieldsNoMap() {
        assertNull(ObjectMapBuilder.objectMap(null, null, "source"));
        assertNull(ObjectMapBuilder.surfaceMap(null, "source"));
        assertNull(ObjectMapBuilder.centroidMap(null, null, "source"));
        assertNull(ObjectMapBuilder.centerOfMassMap(null, null, "source"));
    }

    @Test
    public void untitledSourcesGetAPlaceholderName() {
        ImagePlus labels = Fixtures.cube("", 4, 1, 1, 1, 2, 1);
        ImagePlus map = ObjectMapBuilder.objectMap(labels, null, null);
        assertEquals("Objects map of <untitled>", map.getTitle());
    }

    @Test
    public void surfaceMapKeepsTheShellAndDropsTheInterior() {
        // A 3x3x3 cube well inside a 7^3 stack: its centre voxel is interior.
        ImagePlus labels = Fixtures.cube("source", 7, 2, 2, 2, 3, 1);

        ImagePlus surfaces = ObjectMapBuilder.surfaceMap(labels, "source");

        assertEquals("Surfaces map of source", surfaces.getTitle());
        assertEquals("the centre voxel has six same-label neighbours",
                0, (int) surfaces.getStack().getProcessor(4).getf(3, 3));
        assertEquals("a corner voxel is exposed",
                1, (int) surfaces.getStack().getProcessor(3).getf(2, 2));
    }

    @Test
    public void centroidMapPlacesOneVoxelPerObject() {
        ImagePlus labels = Fixtures.cube("source", 8, 1, 1, 1, 3, 1);
        ResultsTable stats = statsFor(1, 2, 3, 4);

        ImagePlus centroids = ObjectMapBuilder.centroidMap(labels, stats, "source");

        assertEquals("Centroids map of source", centroids.getTitle());
        assertEquals(1, (int) centroids.getStack().getProcessor(5).getf(2, 3));
        assertEquals(0, (int) centroids.getStack().getProcessor(1).getf(0, 0));
    }

    @Test
    public void centerOfMassMapReadsTheWeightedColumns() {
        ImagePlus labels = Fixtures.cube("source", 8, 1, 1, 1, 3, 1);
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue("X", 0, 0);
        stats.setValue("Y", 0, 0);
        stats.setValue("Z", 0, 0);
        stats.setValue("XM", 0, 5);
        stats.setValue("YM", 0, 6);
        stats.setValue("ZM", 0, 2);

        ImagePlus centres = ObjectMapBuilder.centerOfMassMap(labels, stats, "source");

        assertEquals("Centers of mass map of source", centres.getTitle());
        assertEquals(1, (int) centres.getStack().getProcessor(3).getf(5, 6));
        assertEquals("the geometric centroid must not be drawn here",
                0, (int) centres.getStack().getProcessor(1).getf(0, 0));
    }

    @Test
    public void outOfBoundsCoordinatesAreSkippedNotClamped() {
        ImagePlus labels = Fixtures.cube("source", 6, 1, 1, 1, 2, 1);
        ResultsTable stats = statsFor(1, 999, 999, 999);

        ImagePlus centroids = ObjectMapBuilder.centroidMap(labels, stats, "source");

        for (int z = 1; z <= centroids.getStackSize(); z++) {
            ImageProcessor processor = centroids.getStack().getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                assertEquals("nothing should be drawn for an off-image centroid",
                        0, (int) processor.getf(i));
            }
        }
    }

    @Test
    public void mapBitDepthFollowsTheLabelCount() {
        ImagePlus small = labelStack(200);
        ImagePlus medium = labelStack(300);
        ImagePlus large = labelStack(70000);

        assertTrue(ObjectMapBuilder.surfaceMap(small, "small")
                .getStack().getProcessor(1) instanceof ByteProcessor);
        assertTrue(ObjectMapBuilder.surfaceMap(medium, "medium")
                .getStack().getProcessor(1) instanceof ShortProcessor);
        assertTrue("above 65,535 a ShortProcessor would wrap object 65,536 to background",
                ObjectMapBuilder.surfaceMap(large, "large")
                        .getStack().getProcessor(1) instanceof FloatProcessor);
    }

    @Test
    public void mapsInheritCalibration() {
        ImagePlus labels = Fixtures.calibrate(
                Fixtures.cube("source", 6, 1, 1, 1, 2, 1), 0.25, 0.25, 1.0, "mm");

        ImagePlus surfaces = ObjectMapBuilder.surfaceMap(labels, "source");

        assertEquals(0.25, surfaces.getCalibration().pixelWidth, 0.0);
        assertEquals(1.0, surfaces.getCalibration().pixelDepth, 0.0);
        assertEquals("mm", surfaces.getCalibration().getUnit());
    }

    @Test
    public void aNumberOverlayIsAddedForEveryObjectInTheTable() {
        ImagePlus labels = Fixtures.blank("source", 8, 8, 3, 8);
        labels.getStack().getProcessor(1).setf(1, 1, 1);
        labels.getStack().getProcessor(2).setf(4, 4, 2);

        ResultsTable stats = new ResultsTable();
        addRow(stats, 0, 1, 1, 1, 0);
        addRow(stats, 1, 2, 4, 4, 1);

        ImagePlus map = ObjectMapBuilder.objectMapInPlace(labels, stats, "source");

        assertNotNull(map.getOverlay());
        assertEquals(2, map.getOverlay().size());
        assertNull("nothing was skipped", ObjectMapBuilder.overlaySkippedReason(map));
    }

    @Test
    public void theOverlayIsAlwaysDrawnRegardlessOfTheHistoricLabelCap() {
        String previous = System.getProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
        System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, "1");
        try {
            ImagePlus labels = Fixtures.blank("source", 8, 8, 1, 8);
            labels.getStack().getProcessor(1).setf(1, 1, 1);
            labels.getStack().getProcessor(1).setf(3, 3, 2);
            labels.getStack().getProcessor(1).setf(5, 5, 3);

            ResultsTable stats = new ResultsTable();
            addRow(stats, 0, 1, 1, 1, 0);
            addRow(stats, 1, 2, 3, 3, 0);
            addRow(stats, 2, 3, 5, 5, 0);

            ImagePlus map = ObjectMapBuilder.objectMapInPlace(labels, stats, "source");

            assertEquals("the cap is documented as not enforced", 3, map.getOverlay().size());
            assertNull(map.getProperty(ObjectMapBuilder.OVERLAY_SKIPPED_PROPERTY));
        } finally {
            if (previous == null) {
                System.clearProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
            } else {
                System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, previous);
            }
        }
    }

    @Test
    public void overlaySkippedReasonReadsTheImageProperties() {
        ImagePlus map = Fixtures.blank("map", 4, 4, 1, 8);
        assertNull(ObjectMapBuilder.overlaySkippedReason(null));
        assertNull(ObjectMapBuilder.overlaySkippedReason(map));

        map.setProperty(ObjectMapBuilder.OVERLAY_SKIPPED_PROPERTY, Boolean.TRUE);
        assertEquals("Object-number overlay skipped.", ObjectMapBuilder.overlaySkippedReason(map));

        map.setProperty(ObjectMapBuilder.OVERLAY_SKIPPED_REASON_PROPERTY, "too many labels");
        assertEquals("too many labels", ObjectMapBuilder.overlaySkippedReason(map));
    }

    @Test
    public void aTableWithoutALabelColumnFallsBackToRowOrder() {
        ImagePlus labels = Fixtures.blank("source", 8, 8, 1, 8);
        labels.getStack().getProcessor(1).setf(2, 2, 1);

        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("X", 0, 2);
        stats.setValue("Y", 0, 2);
        stats.setValue("Z", 0, 0);

        ImagePlus centroids = ObjectMapBuilder.centroidMap(labels, stats, "source");

        assertEquals("row 0 means label 1", 1, (int) centroids.getStack().getProcessor(1).getf(2, 2));
    }

    @Test
    public void theMemoryGuardNamesTheMapAndTheSizes() {
        String previous =
                System.getProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
        System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY,
                Long.toString(Long.MAX_VALUE / 2));
        try {
            ImagePlus labels = Fixtures.cube("source", 8, 1, 1, 1, 2, 1);
            ObjectMapBuilder.surfaceMap(labels, "source");
            fail("expected OptionalMapMemoryException");
        } catch (ObjectMapBuilder.OptionalMapMemoryException expected) {
            assertEquals("Surfaces", expected.mapName());
            assertTrue(expected.estimatedBytes() > 0);
            assertTrue(expected.reserveBytes() > 0);
            assertTrue(expected.getMessage(), expected.getMessage().contains("Surfaces"));
            assertTrue(ObjectMapBuilder.isMemoryGuardFailure(expected));
            assertTrue(ObjectMapBuilder.isMemoryGuardFailure(
                    new RuntimeException("wrapped", expected)));
        } finally {
            if (previous == null) {
                System.clearProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
            } else {
                System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY, previous);
            }
        }
    }

    @Test
    public void unrelatedFailuresAreNotReportedAsMemoryGuardFailures() {
        assertTrue(!ObjectMapBuilder.isMemoryGuardFailure(null));
        assertTrue(!ObjectMapBuilder.isMemoryGuardFailure(new RuntimeException("something else")));
    }

    private static ImagePlus labelStack(int maxLabel) {
        ImagePlus image = Fixtures.blank("labels", 4, 4, 1, maxLabel <= 255 ? 8 : 32);
        image.getStack().getProcessor(1).setf(1, 1, maxLabel);
        return image;
    }

    private static ResultsTable statsFor(int label, double x, double y, double z) {
        ResultsTable stats = new ResultsTable();
        addRow(stats, 0, label, x, y, z);
        return stats;
    }

    private static void addRow(ResultsTable stats, int row, int label, double x, double y, double z) {
        stats.incrementCounter();
        stats.setValue("Label", row, label);
        stats.setValue("X", row, x);
        stats.setValue("Y", row, y);
        stats.setValue("Z", row, z);
    }
}
