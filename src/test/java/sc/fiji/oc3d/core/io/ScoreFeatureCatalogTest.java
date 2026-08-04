package sc.fiji.oc3d.core.io;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScoreFeatureCatalogTest {

    @Test
    public void calibratedHeadingsCollapseOntoStableNames() {
        assertEquals(ScoreFeatureCatalog.VOLUME,
                ScoreFeatureCatalog.canonicalFeature("Volume (um^3)"));
        assertEquals(ScoreFeatureCatalog.VOLUME,
                ScoreFeatureCatalog.canonicalFeature("Volume (pixel^3)"));
        assertEquals(ScoreFeatureCatalog.SURFACE,
                ScoreFeatureCatalog.canonicalFeature("Surface (mm^2)"));
    }

    @Test
    public void coordinatesAndLabelsAreNotScored() {
        assertNull(ScoreFeatureCatalog.canonicalFeature("X"));
        assertNull(ScoreFeatureCatalog.canonicalFeature("BX"));
        assertNull(ScoreFeatureCatalog.canonicalFeature("Label"));
        assertNull(ScoreFeatureCatalog.canonicalFeature("Mean"));
        assertNull(ScoreFeatureCatalog.canonicalFeature(null));
        assertFalse(ScoreFeatureCatalog.isScoreable("XM"));
    }

    @Test
    public void shapeColumnsAreScored() {
        assertTrue(ScoreFeatureCatalog.isScoreable("Morph_Sphericity"));
        assertTrue(ScoreFeatureCatalog.isScoreable("Morph_Compactness"));
        assertTrue(ScoreFeatureCatalog.isScoreable("Morph_Elongation"));
        assertTrue(ScoreFeatureCatalog.isScoreable("Nb of obj. voxels"));
    }

    @Test
    public void dimensionPowerDrivesUnitConversion() {
        assertEquals(3, ScoreFeatureCatalog.physicalDimensionPower(ScoreFeatureCatalog.VOLUME));
        assertEquals(2, ScoreFeatureCatalog.physicalDimensionPower(ScoreFeatureCatalog.SURFACE));
        assertEquals(1, ScoreFeatureCatalog.physicalDimensionPower("Morph_Feret3D_um"));
        assertEquals(0, ScoreFeatureCatalog.physicalDimensionPower("Morph_Sphericity"));
    }

    @Test
    public void aColumnAlreadyInMicrometresIsNotConvertedAgain() {
        assertEquals("converting a value already in um would scale it twice",
                0, ScoreFeatureCatalog.physicalDimensionPower("Morph_ShollCriticalRadius_um"));
        assertEquals("um", ScoreFeatureCatalog.scoringUnit("Morph_ShollCriticalRadius_um"));
    }

    @Test
    public void scoringUnitsFollowTheDimension() {
        assertEquals("um^3", ScoreFeatureCatalog.scoringUnit(ScoreFeatureCatalog.VOLUME));
        assertEquals("um^2", ScoreFeatureCatalog.scoringUnit(ScoreFeatureCatalog.SURFACE));
        assertEquals("um", ScoreFeatureCatalog.scoringUnit("Morph_Feret3D_um"));
        assertEquals("voxel", ScoreFeatureCatalog.scoringUnit("Nb of obj. voxels"));
        assertEquals("voxel", ScoreFeatureCatalog.scoringUnit("Nb of surf. voxels"));
        assertEquals("voxel", ScoreFeatureCatalog.scoringUnit("Morph_SkeletonVoxels"));
        assertEquals("count", ScoreFeatureCatalog.scoringUnit("Morph_SkeletonBranches"));
        assertEquals("count", ScoreFeatureCatalog.scoringUnit("Morph_ShollCriticalIntersections"));
        assertEquals("unitless", ScoreFeatureCatalog.scoringUnit("Morph_Sphericity"));
        assertEquals("unitless", ScoreFeatureCatalog.scoringUnit(null));
    }

    @Test
    public void registeredFeaturesBecomeScoreable() {
        String heading = "Morph_ScoreCatalogTestFeature";
        assertFalse(ScoreFeatureCatalog.isScoreable(heading));

        ScoreFeatureCatalog.registerFeatures(Arrays.asList(heading, null, "  "));

        assertTrue(ScoreFeatureCatalog.isScoreable(heading));
        assertTrue(ScoreFeatureCatalog.features().contains(heading));
        assertTrue(ScoreFeatureCatalog.features().contains(ScoreFeatureCatalog.VOLUME));
    }

    @Test
    public void theFeatureSetIsAnImmutableSnapshot() {
        java.util.Set<String> features = ScoreFeatureCatalog.features();
        try {
            features.add("anything");
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertFalse(ScoreFeatureCatalog.isScoreable("anything"));
        }
    }
}
