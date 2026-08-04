package sc.fiji.oc3d.core.ui;

import ij.ImagePlus;

import org.junit.Test;

import java.util.List;

import sc.fiji.oc3d.core.api.MorphPredicate;
import sc.fiji.oc3d.core.macro.MacroFilters;
import sc.fiji.oc3d.core.testing.Fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DialogModelTest {

    @Test
    public void aFreshModelImpliesNoFilters() {
        assertTrue("every range starts at a non-excluding default",
                new DialogModel().enabledPredicates().isEmpty());
    }

    @Test
    public void onlyTightenedRangesBecomePredicates() {
        DialogModel model = new DialogModel();
        range(model, "sphericity").minText = "0.6";

        List<MorphPredicate> predicates = model.enabledPredicates();

        assertEquals(1, predicates.size());
        assertEquals("sphericity", predicates.get(0).featureName);
        assertEquals(MorphPredicate.Operator.GE, predicates.get(0).op);
        assertEquals(0.6, predicates.get(0).value, 0.0);
    }

    @Test
    public void bothEndsOfARangeCanFilter() {
        DialogModel model = new DialogModel();
        DialogModel.FeatureRange elongation = range(model, "elongation");
        elongation.minText = "1.5";
        elongation.maxText = "9";

        List<MorphPredicate> predicates = model.enabledPredicates();

        assertEquals(2, predicates.size());
        assertEquals(MorphPredicate.Operator.GE, predicates.get(0).op);
        assertEquals(MorphPredicate.Operator.LE, predicates.get(1).op);
    }

    @Test
    public void explicitFilterRowsAreIncludedOnlyWhenEnabled() {
        DialogModel model = new DialogModel();
        model.addFilter(new DialogModel.FilterRow("volume", ">", 100, true));
        model.addFilter(new DialogModel.FilterRow("volume", "<", 5, false));

        List<MorphPredicate> predicates = model.enabledPredicates();

        assertEquals(1, predicates.size());
        assertEquals(MorphPredicate.Operator.GT, predicates.get(0).op);
        assertEquals(100.0, predicates.get(0).value, 0.0);
    }

    @Test
    public void removeFilterToleratesOutOfRangeIndices() {
        DialogModel model = new DialogModel();
        model.addFilter(new DialogModel.FilterRow("volume", ">=", 1, true));
        model.addFilter(null);
        model.removeFilter(-1);
        model.removeFilter(99);
        assertEquals(1, model.filters().size());
        model.removeFilter(0);
        assertTrue(model.filters().isEmpty());
    }

    @Test
    public void calibratedImagesGainAVolumeRowAndUncalibratedOnesDoNot() {
        DialogModel model = new DialogModel();
        assertNull(rangeOrNull(model, "volume_calibrated"));

        ImagePlus calibrated = Fixtures.calibrate(
                Fixtures.blank("calibrated", 4, 4, 2, 8), 0.5, 0.5, 1.0, "mm");
        model.configureForImage(calibrated);
        assertEquals("Volume (mm^3)", range(model, "volume_calibrated").label);

        ImagePlus uncalibrated = Fixtures.blank("uncalibrated", 4, 4, 2, 8);
        model.configureForImage(uncalibrated);
        assertNull("a pixel unit is not a spatial calibration",
                rangeOrNull(model, "volume_calibrated"));
    }

    @Test
    public void calibratedVolumeUnitRejectsPlaceholderUnits() {
        assertNull(DialogModel.calibratedVolumeUnit(null));
        assertNull(DialogModel.calibratedVolumeUnit(
                Fixtures.calibrate(Fixtures.blank("i", 2, 2, 1, 8), 1, 1, 1, "pixel")));
        assertNull(DialogModel.calibratedVolumeUnit(
                Fixtures.calibrate(Fixtures.blank("i", 2, 2, 1, 8), 0, 1, 1, "mm")));
        assertEquals("mm", DialogModel.calibratedVolumeUnit(
                Fixtures.calibrate(Fixtures.blank("i", 2, 2, 1, 8), 1, 1, 1, "mm")));
    }

    @Test
    public void validateCollectsEveryProblemNotJustTheFirst() {
        DialogModel model = new DialogModel();
        model.minSize = -1;
        model.maxSize = -5;
        range(model, "sphericity").maxText = "2";
        model.addFilter(new DialogModel.FilterRow("volume", "==", 1, true));

        List<String> errors = model.validate();

        assertTrue("expected several errors, got " + errors, errors.size() >= 3);
        assertTrue(errors.toString(), containing(errors, "Min size"));
        assertTrue(errors.toString(), containing(errors, "Sphericity"));
        assertTrue(errors.toString(), containing(errors, "operator"));
    }

    @Test
    public void aValidModelReportsNoErrors() {
        DialogModel model = new DialogModel();
        model.minSize = 10;
        model.maxSize = 5000;
        range(model, "sphericity").minText = "0.4";
        assertTrue(model.validate().toString(), model.validate().isEmpty());
    }

    @Test
    public void invertedRangesAreRejected() {
        DialogModel model = new DialogModel();
        DialogModel.FeatureRange sphericity = range(model, "sphericity");
        sphericity.minText = "0.9";
        sphericity.maxText = "0.1";

        assertTrue(containing(model.validate(), "minimum must be <= maximum"));
        assertFalse(sphericity.accepts("0.9", "0.1"));
        assertTrue(sphericity.accepts("0.1", "0.9"));
    }

    @Test
    public void infiniteBoundsAreRejectedWhereTheyWouldExcludeEverything() {
        DialogModel model = new DialogModel();
        range(model, "surface_area").minText = "Infinity";
        assertTrue(containing(model.validate(), "minimum cannot be Infinity"));

        DialogModel other = new DialogModel();
        range(other, "surface_area").maxText = "-Infinity";
        assertTrue(containing(other.validate(), "maximum cannot be -Infinity"));
    }

    @Test
    public void anUnsafeRedirectTitleIsRejected() {
        DialogModel model = new DialogModel();
        model.redirectTitle = "bad ] title";
        assertTrue(containing(model.validate(), "Redirect image title"));
    }

    @Test
    public void parseRangeBoundAcceptsTheDisplayedInfinityForms() {
        assertEquals(Double.POSITIVE_INFINITY, DialogModel.parseRangeBound("Infinity", "f"), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, DialogModel.parseRangeBound("+inf", "f"), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, DialogModel.parseRangeBound("-Infinity", "f"), 0.0);
        assertEquals(2.5, DialogModel.parseRangeBound(" 2.5 ", "f"), 0.0);
        assertRangeBoundRejected(null);
        assertRangeBoundRejected("");
        assertRangeBoundRejected("NaN");
        assertRangeBoundRejected("abc");
    }

    @Test
    public void macroOptionsRoundTripThroughTheFilterParser() {
        DialogModel model = new DialogModel();
        model.minSize = 25;
        model.maxSize = 900;
        model.excludeOnEdges = true;
        model.redirectTitle = "dapi.tif";
        range(model, "sphericity").minText = "0.6";
        model.showCentroids = false;

        String options = model.toMacroOptions();

        assertTrue(options, options.contains("min=25"));
        assertTrue(options, options.contains("max=900"));
        assertTrue(options, options.contains("exclude_edges"));
        assertTrue(options, options.contains("redirect=[dapi.tif]"));
        assertTrue(options, options.contains("sphericity>=0.6"));
        assertTrue(options, options.contains("hide_centroids"));
        assertFalse(options, options.contains("hide_labels"));

        List<MorphPredicate> reparsed = MacroFilters.parse(options);
        assertEquals(1, reparsed.size());
        assertEquals("sphericity", reparsed.get(0).featureName);
        assertEquals(0.6, reparsed.get(0).value, 1e-12);
    }

    @Test
    public void unboundedMaxSizeIsWrittenAsInfinity() {
        assertTrue(new DialogModel().toMacroOptions().contains("max=Infinity"));
    }

    @Test
    public void macroOptionsHaveNoLeadingOrDoubledSpaces() {
        String options = new DialogModel().toMacroOptions();
        assertFalse(options, options.startsWith(" "));
        assertFalse(options, options.contains("  "));
    }

    @Test
    public void snapshotIsIndependentOfTheOriginal() {
        DialogModel model = new DialogModel();
        model.minSize = 5;
        range(model, "sphericity").minText = "0.3";
        model.addFilter(new DialogModel.FilterRow("volume", ">=", 3, true));

        DialogModel snapshot = model.snapshot();

        model.minSize = 999;
        range(model, "sphericity").minText = "0.9";
        model.filters().get(0).value = 999;

        assertEquals(5, snapshot.minSize);
        assertEquals("0.3", range(snapshot, "sphericity").minText);
        assertEquals(3.0, snapshot.filters().get(0).value, 0.0);
    }

    @Test
    public void copyFromNullIsANoOp() {
        DialogModel model = new DialogModel();
        model.minSize = 12;
        model.copyFrom(null);
        assertEquals(12, model.minSize);
    }

    @Test
    public void subclassHooksReachTheMacroStringAndTheSnapshot() {
        WithThreshold model = new WithThreshold();
        model.threshold = 77;
        model.measureExtras = true;

        String options = model.toMacroOptions();
        assertTrue(options, options.startsWith("threshold=77 min="));
        assertTrue(options, options.contains("measure_extras"));

        WithThreshold snapshot = (WithThreshold) model.snapshot();
        assertEquals(77, snapshot.threshold);
        assertTrue(snapshot.measureExtras);
    }

    @Test
    public void additionalRangesOnlyFilterWhileTheirToggleIsOn() {
        WithThreshold model = new WithThreshold();
        model.extraRange.minText = "4";

        assertTrue("a disabled measurement's range must not filter",
                model.enabledPredicates().isEmpty());

        model.measureExtras = true;
        List<MorphPredicate> predicates = model.enabledPredicates();
        assertEquals(1, predicates.size());
        assertEquals("extra_feature", predicates.get(0).featureName);
    }

    @Test
    public void featureAndOperatorOptionsAreOffered() {
        assertTrue(DialogModel.featureOptions().contains("sphericity"));
        assertTrue(DialogModel.featureOptions().contains("feret_diameter_max"));
        assertEquals(4, DialogModel.operatorOptions().size());
        assertTrue(DialogModel.operatorOptions().contains(">="));
    }

    private static final class WithThreshold extends DialogModel {
        int threshold = 128;
        boolean measureExtras;
        final FeatureRange extraRange = new FeatureRange(
                "extra_feature", "Extra feature", "0", "Infinity", 0, Double.POSITIVE_INFINITY);

        @Override
        protected void appendEngineMacroOptions(StringBuilder options) {
            append(options, "threshold=" + threshold);
        }

        @Override
        protected void appendExtraMacroFlags(StringBuilder options) {
            if (measureExtras) append(options, "measure_extras");
        }

        @Override
        protected List<DialogModel.FeatureRange> activeAdditionalRanges() {
            return measureExtras
                    ? java.util.Collections.singletonList(extraRange)
                    : java.util.Collections.<DialogModel.FeatureRange>emptyList();
        }

        @Override
        protected void copyAdditionalFrom(DialogModel other) {
            if (!(other instanceof WithThreshold)) return;
            WithThreshold source = (WithThreshold) other;
            threshold = source.threshold;
            measureExtras = source.measureExtras;
            extraRange.minText = source.extraRange.minText;
            extraRange.maxText = source.extraRange.maxText;
        }

        @Override
        protected DialogModel newInstance() {
            return new WithThreshold();
        }
    }

    private static DialogModel.FeatureRange range(DialogModel model, String feature) {
        DialogModel.FeatureRange found = rangeOrNull(model, feature);
        if (found == null) fail("no range for feature '" + feature + "'");
        return found;
    }

    private static DialogModel.FeatureRange rangeOrNull(DialogModel model, String feature) {
        for (DialogModel.FeatureRange range : model.featureRanges()) {
            if (range.feature.equals(feature)) return range;
        }
        return null;
    }

    private static boolean containing(List<String> messages, String fragment) {
        for (String message : messages) {
            if (message != null && message.contains(fragment)) return true;
        }
        return false;
    }

    private static void assertRangeBoundRejected(String text) {
        try {
            DialogModel.parseRangeBound(text, "Field");
            fail("expected IllegalArgumentException for '" + text + "'");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Field"));
        }
    }
}
