package sc.fiji.oc3d.core.ingest;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LabelUtilsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void roisBecomeLabelsInFileOrder() {
        ImagePlus reference = Fixtures.blank("reference", 20, 20, 1, 8);
        Roi[] rois = {new Roi(1, 1, 4, 4), new Roi(10, 10, 4, 4)};

        ImagePlus labels = LabelUtils.roiSetToLabelImage(reference, rois);
        ImageProcessor slice = labels.getStack().getProcessor(1);

        assertEquals(1, (int) slice.getf(2, 2));
        assertEquals(2, (int) slice.getf(11, 11));
        assertEquals(0, (int) slice.getf(18, 18));
    }

    @Test
    public void aRoiWithoutAZPositionIsDrawnOnEverySlice() {
        ImagePlus reference = Fixtures.blank("reference", 12, 12, 4, 8);
        Roi[] rois = {new Roi(2, 2, 3, 3)};

        ImagePlus labels = LabelUtils.roiSetToLabelImage(reference, rois);

        for (int z = 1; z <= 4; z++) {
            assertEquals("a 2D ROI is a column through the stack",
                    1, (int) labels.getStack().getProcessor(z).getf(3, 3));
        }
    }

    @Test
    public void aRoiWithAZPositionIsDrawnOnThatSliceOnly() {
        ImagePlus reference = Fixtures.blank("reference", 12, 12, 4, 8);
        Roi roi = new Roi(2, 2, 3, 3);
        roi.setPosition(0, 3, 0);

        ImagePlus labels = LabelUtils.roiSetToLabelImage(reference, new Roi[] {roi});

        assertEquals(0, (int) labels.getStack().getProcessor(1).getf(3, 3));
        assertEquals(1, (int) labels.getStack().getProcessor(3).getf(3, 3));
        assertEquals(0, (int) labels.getStack().getProcessor(4).getf(3, 3));
    }

    @Test
    public void anOvalRoiUsesItsMaskNotItsBoundingBox() {
        ImagePlus reference = Fixtures.blank("reference", 20, 20, 1, 8);
        Roi[] rois = {new OvalRoi(2, 2, 10, 10)};

        ImageProcessor slice = LabelUtils.roiSetToLabelImage(reference, rois)
                .getStack().getProcessor(1);

        assertEquals("the oval's centre is inside", 1, (int) slice.getf(7, 7));
        assertEquals("the bounding box corner is outside the oval",
                0, (int) slice.getf(2, 2));
    }

    @Test
    public void bitDepthFollowsTheRoiCountSoLargeSetsDoNotWrap() {
        ImagePlus reference = Fixtures.blank("reference", 4, 4, 1, 8);

        assertTrue(LabelUtils.roiSetToLabelImage(reference, rois(10))
                .getStack().getProcessor(1) instanceof ByteProcessor);
        assertTrue(LabelUtils.roiSetToLabelImage(reference, rois(300))
                .getStack().getProcessor(1) instanceof ShortProcessor);
        assertTrue("a 16-bit image would turn ROI 65,536 into background",
                LabelUtils.roiSetToLabelImage(reference, rois(70000))
                        .getStack().getProcessor(1) instanceof FloatProcessor);
    }

    @Test
    public void calibrationIsCopiedFromTheReference() {
        ImagePlus reference = Fixtures.calibrate(
                Fixtures.blank("reference", 10, 10, 2, 8), 0.3, 0.3, 1.5, "mm");

        ImagePlus labels = LabelUtils.roiSetToLabelImage(reference, new Roi[] {new Roi(1, 1, 2, 2)});

        assertEquals(0.3, labels.getCalibration().pixelWidth, 0.0);
        assertEquals(1.5, labels.getCalibration().pixelDepth, 0.0);
        assertEquals("mm", labels.getCalibration().getUnit());
    }

    @Test
    public void overlappingRoisResolveToTheLaterLabel() {
        ImagePlus reference = Fixtures.blank("reference", 12, 12, 1, 8);
        Roi[] rois = {new Roi(1, 1, 6, 6), new Roi(3, 3, 6, 6)};

        ImageProcessor slice = LabelUtils.roiSetToLabelImage(reference, rois)
                .getStack().getProcessor(1);

        assertEquals("a voxel cannot belong to two objects", 2, (int) slice.getf(4, 4));
        assertEquals(1, (int) slice.getf(1, 1));
    }

    @Test
    public void nullEntriesInTheArrayAreSkipped() {
        ImagePlus reference = Fixtures.blank("reference", 10, 10, 1, 8);
        Roi[] rois = {null, new Roi(1, 1, 3, 3)};

        ImageProcessor slice = LabelUtils.roiSetToLabelImage(reference, rois)
                .getStack().getProcessor(1);

        assertEquals("labels still follow array position", 2, (int) slice.getf(2, 2));
    }

    @Test
    public void nullArgumentsAreRejected() {
        try {
            LabelUtils.roiSetToLabelImage(null, new Roi[0]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reference"));
        }
        try {
            LabelUtils.roiSetToLabelImage(Fixtures.blank("r", 4, 4, 1, 8), null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rois"));
        }
    }

    @Test
    public void loadsASingleRoiFile() throws IOException {
        File file = folder.newFile("one.roi");
        RoiEncoder.save(new Roi(2, 3, 4, 5), file.getAbsolutePath());

        Roi[] loaded = LabelUtils.loadRoiSet(file.getAbsolutePath());

        assertEquals(1, loaded.length);
        assertEquals(2, loaded[0].getBounds().x);
        assertEquals(4, loaded[0].getBounds().width);
    }

    @Test
    public void loadsAZipRoiSetAndIgnoresNonRoiEntries() throws IOException {
        File zip = folder.newFile("set.zip");
        OutputStream out = new FileOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(out);
        try {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("not an roi".getBytes("UTF-8"));
            zos.closeEntry();
            writeRoi(zos, "0001-a.roi", new Roi(1, 1, 2, 2));
            writeRoi(zos, "0002-b.roi", new Roi(5, 5, 2, 2));
        } finally {
            zos.close();
        }

        Roi[] loaded = LabelUtils.loadRoiSet(zip.getAbsolutePath());

        assertEquals(2, loaded.length);
        assertEquals(1, loaded[0].getBounds().x);
        assertEquals(5, loaded[1].getBounds().x);
    }

    @Test
    public void aBlankPathIsRejected() {
        try {
            LabelUtils.loadRoiSet("  ");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("path"));
        } catch (IOException unexpected) {
            fail("expected IllegalArgumentException, got " + unexpected);
        }
    }

    @Test
    public void theFacadeRejectsAnEmptyRoiSet() {
        ImagePlus reference = Fixtures.blank("reference", 8, 8, 1, 8);
        try {
            RoiLabelImages.fromRois(reference, new Roi[0]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("zero objects"));
        }
    }

    @Test
    public void theFacadeTitlesTheResultAfterTheRoiFile() throws IOException {
        File file = folder.newFile("cells_exp3.roi");
        RoiEncoder.save(new Roi(1, 1, 3, 3), file.getAbsolutePath());
        ImagePlus reference = Fixtures.blank("reference", 10, 10, 1, 8);

        ImagePlus labels = RoiLabelImages.fromRoiSetFile(reference, file.getAbsolutePath());

        assertEquals("in batch the ROI file is what distinguishes the run",
                "cells_exp3", labels.getTitle());
    }

    @Test
    public void theFacadeRejectsABlankPath() throws IOException {
        try {
            RoiLabelImages.fromRoiSetFile(Fixtures.blank("r", 4, 4, 1, 8), null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("path"));
        }
    }

    private static void writeRoi(ZipOutputStream zos, String name, Roi roi) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(RoiEncoder.saveAsByteArray(roi));
        zos.closeEntry();
    }

    private static Roi[] rois(int count) {
        Roi[] rois = new Roi[count];
        for (int i = 0; i < count; i++) {
            rois[i] = new Roi(0, 0, 1, 1);
        }
        return rois;
    }
}
