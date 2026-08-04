package sc.fiji.oc3d.core.measure;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.Test;

import java.util.List;

import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LabelFeatureAccumulatorTest {

    private static final double EPS = 1e-9;

    @Test
    public void countsVoxelsAndBoundingBoxOfASolidCube() {
        ImagePlus labels = Fixtures.cube("labels", 10, 2, 3, 4, 3, 1);

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);
        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);

        assertEquals(27, values.voxelCount());
        assertEquals(2, values.boundingX());
        assertEquals(3, values.boundingY());
        assertEquals(4, values.boundingZ());
        assertEquals(3, values.boundingWidth());
        assertEquals(3, values.boundingHeight());
        assertEquals(3, values.boundingDepth());
        assertEquals(3.0, values.centroidX(), EPS);
        assertEquals(4.0, values.centroidY(), EPS);
        assertEquals(5.0, values.centroidZ(), EPS);
    }

    @Test
    public void surfaceCountsEveryExposedFace() {
        // A free-standing 3x3x3 cube: 26 of its 27 voxels are on the surface,
        // and it exposes 9 faces on each of 6 sides.
        ImagePlus labels = Fixtures.cube("labels", 10, 2, 2, 2, 3, 1);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertEquals(26, values.surfaceVoxelCount());
        assertEquals(54.0, values.surfaceArea(), EPS);
    }

    @Test
    public void imageBorderCountsAsExposedSurface() {
        // A cube flush against x=0 still exposes that face: there is nothing
        // beyond the image to be adjacent to.
        ImagePlus flush = Fixtures.cube("flush", 6, 0, 1, 1, 3, 1);
        ImagePlus free = Fixtures.cube("free", 6, 1, 1, 1, 3, 1);

        double flushArea = LabelFeatureAccumulator.scan(flush, null, null)
                .valuesForLabel(1).surfaceArea();
        double freeArea = LabelFeatureAccumulator.scan(free, null, null)
                .valuesForLabel(1).surfaceArea();

        assertEquals(freeArea, flushArea, EPS);
    }

    @Test
    public void volumeAndSurfaceFollowAnisotropicCalibration() {
        ImagePlus labels = Fixtures.cube("labels", 10, 2, 2, 2, 3, 1);
        Fixtures.calibrate(labels, 0.5, 0.5, 2.0, "mm");

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);
        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);

        assertEquals("mm", result.unit());
        assertEquals(27 * 0.5 * 0.5 * 2.0, values.calibratedVolume(), EPS);
        // 9 faces on each pair: yz faces are 0.5*2, xz faces are 0.5*2, xy faces are 0.5*0.5.
        double expected = 2 * 9 * (0.5 * 2.0) + 2 * 9 * (0.5 * 2.0) + 2 * 9 * (0.5 * 0.5);
        assertEquals(expected, values.surfaceArea(), EPS);
    }

    @Test
    public void shapeIgnoresCalibrationSoSphericityMeansOneThing() {
        ImagePlus isotropic = Fixtures.cube("iso", 10, 2, 2, 2, 3, 1);
        ImagePlus anisotropic = Fixtures.cube("aniso", 10, 2, 2, 2, 3, 1);
        Fixtures.calibrate(anisotropic, 0.2, 0.2, 5.0, "mm");

        double isoSphericity = LabelFeatureAccumulator.scan(isotropic, null, null)
                .valuesForLabel(1).sphericity();
        double anisoSphericity = LabelFeatureAccumulator.scan(anisotropic, null, null)
                .valuesForLabel(1).sphericity();

        assertTrue(Double.isFinite(isoSphericity));
        assertEquals(isoSphericity, anisoSphericity, EPS);
    }

    @Test
    public void sphericityIsTheCubeRootOfCompactnessAndNearOneForACube() {
        ImagePlus labels = Fixtures.cube("labels", 12, 2, 2, 2, 6, 1);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertEquals(Math.cbrt(values.compactness()), values.sphericity(), EPS);
        assertTrue("a solid cube should be reasonably compact, was " + values.sphericity(),
                values.sphericity() > 0.6 && values.sphericity() <= 1.3);
    }

    @Test
    public void isolatedVoxelHasNoCorrectedSurfaceSoShapeIsUnavailable() {
        ImagePlus labels = Fixtures.blank("labels", 5, 5, 5, 8);
        labels.getStack().getProcessor(3).setf(2, 2, 1);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertEquals(1, values.voxelCount());
        assertEquals(0.0, values.correctedSurfacePixels(), EPS);
        assertTrue(Double.isNaN(values.compactness()));
        assertTrue(Double.isNaN(values.sphericity()));
        assertTrue("elongation needs more than one voxel", Double.isNaN(values.elongation()));
    }

    @Test
    public void elongationIsOneForACubeAndLargerForARod() {
        ImagePlus cube = Fixtures.cube("cube", 12, 2, 2, 2, 4, 1);

        ImagePlus rod = Fixtures.blank("rod", 12, 12, 12, 8);
        for (int x = 1; x <= 9; x++) {
            rod.getStack().getProcessor(6).setf(x, 5, 1);
        }

        double cubeElongation = LabelFeatureAccumulator.scan(cube, null, null)
                .valuesForLabel(1).elongation();
        double rodElongation = LabelFeatureAccumulator.scan(rod, null, null)
                .valuesForLabel(1).elongation();

        assertEquals(1.0, cubeElongation, 1e-6);
        assertTrue("a 9x1x1 rod must read as elongated, was " + rodElongation,
                Double.isNaN(rodElongation) || rodElongation > 2.0);
    }

    @Test
    public void feretIsAtLeastTheAxisAlignedSpanAndNeverOverstates() {
        ImagePlus rod = Fixtures.blank("rod", 20, 20, 5, 8);
        for (int x = 2; x <= 12; x++) {
            rod.getStack().getProcessor(3).setf(x, 10, 1);
        }

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(rod, null, null).valuesForLabel(1);

        // 11 voxels span 10 units centre-to-centre along x.
        assertEquals(10.0, values.feretDiameterMax(), EPS);
    }

    @Test
    public void intensityStatisticsComeFromTheRedirectImage() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 2, 8);
        labels.getStack().getProcessor(1).setf(0, 0, 1);
        labels.getStack().getProcessor(1).setf(1, 0, 1);
        labels.getStack().getProcessor(2).setf(0, 0, 1);

        ImagePlus intensity = Fixtures.blank("intensity", 4, 4, 2, 32);
        intensity.getStack().getProcessor(1).setf(0, 0, 10f);
        intensity.getStack().getProcessor(1).setf(1, 0, 20f);
        intensity.getStack().getProcessor(2).setf(0, 0, 30f);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, intensity, null).valuesForLabel(1);

        assertTrue(values.hasIntensityValues());
        assertEquals(60.0, values.intensitySum(), EPS);
        assertEquals(20.0, values.intensityMean(), EPS);
        assertEquals(10.0, values.intensityMin(), EPS);
        assertEquals(30.0, values.intensityMax(), EPS);
        assertEquals(Math.sqrt((100.0 + 0.0 + 100.0) / 3.0), values.intensityStdDev(), 1e-6);
    }

    @Test
    public void centreOfMassIsPulledTowardsTheBrightVoxel() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 8);
        labels.getStack().getProcessor(1).setf(0, 0, 1);
        labels.getStack().getProcessor(1).setf(2, 0, 1);

        ImagePlus intensity = Fixtures.blank("intensity", 4, 4, 1, 32);
        intensity.getStack().getProcessor(1).setf(0, 0, 1f);
        intensity.getStack().getProcessor(1).setf(2, 0, 3f);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, intensity, null).valuesForLabel(1);

        assertEquals(1.0, values.centroidX(), EPS);
        assertEquals((0 * 1 + 2 * 3) / 4.0, values.centerOfMassX(), EPS);
    }

    @Test
    public void withoutIntensityTheCentreOfMassFallsBackToTheCentroid() {
        ImagePlus labels = Fixtures.cube("labels", 8, 1, 1, 1, 2, 1);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertFalse(values.hasIntensityValues());
        assertTrue(Double.isNaN(values.intensitySum()));
        assertTrue(Double.isNaN(values.intensityMean()));
        assertEquals(values.centroidX(), values.centerOfMassX(), EPS);
        assertEquals(values.centroidY(), values.centerOfMassY(), EPS);
        assertEquals(values.centroidZ(), values.centerOfMassZ(), EPS);
    }

    @Test
    public void nonFiniteIntensityIsSkippedRatherThanPoisoningTheMean() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 8);
        labels.getStack().getProcessor(1).setf(0, 0, 1);
        labels.getStack().getProcessor(1).setf(1, 0, 1);

        ImagePlus intensity = Fixtures.blank("intensity", 4, 4, 1, 32);
        intensity.getStack().getProcessor(1).setf(0, 0, 8f);
        intensity.getStack().getProcessor(1).setf(1, 0, Float.NaN);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, intensity, null).valuesForLabel(1);

        assertEquals(2, values.voxelCount());
        assertEquals(8.0, values.intensityMean(), EPS);
    }

    @Test
    public void backgroundIsZeroNegativeAndNonFinite() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 32);
        ImageProcessor slice = labels.getStack().getProcessor(1);
        slice.setf(0, 0, 0f);
        slice.setf(1, 0, -3f);
        slice.setf(2, 0, Float.NaN);
        slice.setf(3, 0, 2f);

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);

        assertEquals(1, result.objectCount());
        assertNull(result.valuesForLabel(1));
        assertNotNull(result.valuesForLabel(2));
        assertEquals(1, result.valuesForLabel(2).voxelCount());
    }

    @Test
    public void labelsAreReportedInAscendingOrder() {
        ImagePlus labels = Fixtures.blank("labels", 6, 6, 1, 16);
        ImageProcessor slice = labels.getStack().getProcessor(1);
        slice.setf(0, 0, 7);
        slice.setf(1, 0, 3);
        slice.setf(2, 0, 11);

        List<Integer> sorted = LabelFeatureAccumulator.scan(labels, null, null).labelsSorted();

        assertEquals(3, sorted.size());
        assertEquals(Integer.valueOf(3), sorted.get(0));
        assertEquals(Integer.valueOf(7), sorted.get(1));
        assertEquals(Integer.valueOf(11), sorted.get(2));
    }

    @Test
    public void tableHasOneRowPerLabelAndTheDocumentedColumns() {
        ImagePlus labels = Fixtures.blank("labels", 6, 6, 2, 8);
        labels.getStack().getProcessor(1).setf(0, 0, 1);
        labels.getStack().getProcessor(2).setf(4, 4, 2);
        Fixtures.calibrate(labels, 1.0, 1.0, 1.0, "mm");

        ResultsTable table = LabelFeatureAccumulator.scan(labels, null, null).toStatisticsTable();

        assertEquals(2, table.size());
        assertTrue(table.getColumnIndex("Volume (mm^3)") >= 0);
        assertTrue(table.getColumnIndex("Surface (mm^2)") >= 0);
        assertTrue(table.getColumnIndex("Nb of obj. voxels") >= 0);
        assertTrue(table.getColumnIndex("Morph_Sphericity") >= 0);
        assertTrue(table.getColumnIndex("Label") >= 0);
        assertEquals(1.0, table.getValue("Label", 0), EPS);
        assertEquals(2.0, table.getValue("Label", 1), EPS);
    }

    @Test
    public void templateRowOrderIsPreservedAndKeyedByTheLabelColumn() {
        ImagePlus labels = Fixtures.blank("labels", 6, 6, 1, 8);
        ImageProcessor slice = labels.getStack().getProcessor(1);
        slice.setf(0, 0, 1);
        slice.setf(2, 0, 2);
        slice.setf(4, 0, 2);

        ResultsTable template = new ResultsTable();
        template.incrementCounter();
        template.setValue("Label", 0, 2);
        template.incrementCounter();
        template.setValue("Label", 1, 1);

        ResultsTable table = LabelFeatureAccumulator.scan(labels, null, null)
                .toStatisticsTable(template);

        assertEquals(2, table.size());
        assertEquals("row order must follow the template, not the label order",
                2.0, table.getValue("Label", 0), EPS);
        assertEquals(2.0, table.getValue("Nb of obj. voxels", 0), EPS);
        assertEquals(1.0, table.getValue("Label", 1), EPS);
        assertEquals(1.0, table.getValue("Nb of obj. voxels", 1), EPS);
    }

    @Test
    public void templateIsNotMutated() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 8);
        labels.getStack().getProcessor(1).setf(0, 0, 1);

        ResultsTable template = new ResultsTable();
        template.incrementCounter();
        template.setValue("Label", 0, 1);

        LabelFeatureAccumulator.scan(labels, null, null).toStatisticsTable(template);

        assertTrue("the caller's table must be left alone",
                template.getColumnIndex("Nb of obj. voxels") < 0);
    }

    @Test
    public void emptyStatisticsTableCarriesTheFullColumnSet() {
        Calibration calibration = new Calibration();
        calibration.setUnit("mm");
        ResultsTable table = LabelFeatureAccumulator.emptyStatisticsTable(calibration);

        assertEquals(0, table.size());
        assertTrue(table.getColumnIndex("Volume (mm^3)") >= 0);
        assertTrue(table.getColumnIndex("Label") >= 0);
    }

    @Test
    public void columnHeadingsUseTheUnitImageJStores() {
        // ImageJ rewrites "um" to the micro sign, so the heading follows the
        // stored unit rather than the string that was set.
        ImagePlus labels = Fixtures.cube("labels", 6, 1, 1, 1, 2, 1);
        Fixtures.calibrate(labels, 1.0, 1.0, 1.0, "um");
        String stored = labels.getCalibration().getUnit();

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);

        assertEquals(stored, result.unit());
        assertTrue(result.toStatisticsTable().getColumnIndex("Volume (" + stored + "^3)") >= 0);
    }

    @Test
    public void uncalibratedImagesReportPixelUnits() {
        ImagePlus labels = Fixtures.cube("labels", 6, 1, 1, 1, 2, 1);
        assertEquals("pixel", LabelFeatureAccumulator.scan(labels, null, null).unit());
        assertEquals(8.0,
                LabelFeatureAccumulator.scan(labels, null, null)
                        .valuesForLabel(1).calibratedVolume(), EPS);
    }

    @Test
    public void featureLookupMatchesTheAccessors() {
        ImagePlus labels = Fixtures.cube("labels", 8, 1, 1, 1, 3, 1);
        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertEquals(values.voxelCount(), values.feature("volume"), EPS);
        assertEquals(values.calibratedVolume(), values.feature("volume_calibrated"), EPS);
        assertEquals(values.surfaceArea(), values.feature("surface_area"), EPS);
        assertEquals(values.sphericity(), values.feature("sphericity"), EPS);
        assertEquals(values.compactness(), values.feature("compactness"), EPS);
        assertEquals(values.feretDiameterMax(), values.feature("feret_diameter_max"), EPS);
        assertTrue(Double.isNaN(values.feature("not_a_feature")));
        assertTrue(Double.isNaN(values.feature(null)));
    }

    @Test
    public void nullLabelImageIsRejected() {
        try {
            LabelFeatureAccumulator.scan(null, null, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("labelImage"));
        }
    }

    @Test
    public void mismatchedIntensityImageIsRejectedWithBothSizes() {
        ImagePlus labels = Fixtures.blank("labels", 4, 4, 2, 8);
        ImagePlus intensity = Fixtures.blank("intensity", 4, 4, 3, 8);
        try {
            LabelFeatureAccumulator.scan(labels, intensity, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("4x4x3"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("4x4x2"));
        }
    }

    @Test
    public void emptyStackIsRejected() {
        // ImagePlus itself rejects an empty ImageStack, so the guard is
        // exercised through an image that reports no stack at all.
        ImagePlus empty = new ImagePlus("empty", new ByteProcessor(4, 4)) {
            @Override
            public ImageStack getStack() {
                return null;
            }
        };
        try {
            LabelFeatureAccumulator.scan(empty, null, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-empty stack"));
        }
    }

    @Test
    public void explicitCalibrationOverridesTheImagesOwn() {
        ImagePlus labels = Fixtures.cube("labels", 6, 1, 1, 1, 2, 1);
        Fixtures.calibrate(labels, 10.0, 10.0, 10.0, "mm");

        Calibration override = new Calibration();
        override.pixelWidth = 2.0;
        override.pixelHeight = 2.0;
        override.pixelDepth = 2.0;
        override.setUnit("inch");

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, override);

        assertEquals("inch", result.unit());
        assertEquals(8 * 8.0, result.valuesForLabel(1).calibratedVolume(), EPS);
    }

    @Test
    public void nonPositiveCalibrationFallsBackToUnitVoxels() {
        ImagePlus labels = Fixtures.cube("labels", 6, 1, 1, 1, 2, 1);
        Fixtures.calibrate(labels, 0.0, -1.0, Double.NaN, "mm");

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertEquals(8.0, values.calibratedVolume(), EPS);
    }

    @Test
    public void sparseStorageKicksInAboveTheConfiguredDenseLimit() {
        String previous = System.getProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY);
        System.setProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY, "4");
        try {
            ImagePlus labels = Fixtures.blank("labels", 4, 4, 1, 16);
            labels.getStack().getProcessor(1).setf(0, 0, 1000);

            LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);

            assertTrue(result.usesSparseStorage());
            assertEquals(1, result.valuesForLabel(1000).voxelCount());
        } finally {
            if (previous == null) {
                System.clearProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY);
            } else {
                System.setProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY, previous);
            }
        }
    }
}
