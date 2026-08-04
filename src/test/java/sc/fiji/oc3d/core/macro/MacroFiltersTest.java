package sc.fiji.oc3d.core.macro;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sc.fiji.oc3d.core.api.MorphPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MacroFiltersTest {

    @Test
    public void parsesDirectPredicatesAndIgnoresOtherOptions() {
        List<MorphPredicate> filters =
                MacroFilters.parse("threshold=40 sphericity>=0.6 min=10 volume<=500 hide_stats");

        assertEquals(2, filters.size());
        assertEquals("sphericity>=0.6", filters.get(0).format().replace(".6", ".6"));
        assertEquals("sphericity", filters.get(0).featureName);
        assertEquals(MorphPredicate.Operator.GE, filters.get(0).op);
        assertEquals("volume", filters.get(1).featureName);
        assertEquals(MorphPredicate.Operator.LE, filters.get(1).op);
        assertEquals(500.0, filters.get(1).value, 0.0);
    }

    @Test
    public void longerFeatureNamesWinOverTheirPrefixes() {
        // Registration order must not matter: "volume" is listed first here.
        List<String> features = Arrays.asList("volume", "volume_calibrated");
        List<MorphPredicate> filters = MacroFilters.parse("volume_calibrated>=12.5", features);

        assertEquals(1, filters.size());
        assertEquals("volume_calibrated", filters.get(0).featureName);
        assertEquals(12.5, filters.get(0).value, 0.0);
    }

    @Test
    public void bracketedValuesAreNeverReadAsFilters() {
        List<MorphPredicate> filters =
                MacroFilters.parse("redirect=[image with > in title] volume>=1");
        assertEquals(1, filters.size());
        assertEquals("volume", filters.get(0).featureName);
    }

    @Test
    public void unknownFeatureThatLooksLikeAPredicateIsRejected() {
        try {
            MacroFilters.parse("not_a_feature>=1");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not_a_feature>=1"));
            assertTrue("the message should list what is supported",
                    expected.getMessage().contains("sphericity"));
        }
    }

    @Test
    public void equalsInsteadOfAComparisonIsRejected() {
        try {
            MacroFilters.parse("sphericity=0.6");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("feature>=value"));
        }
    }

    @Test
    public void theRemovedIndexedSyntaxIsExplained() {
        try {
            MacroFilters.parse("filter1=sphericity>=0.6");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("filter1"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("no longer supported"));
        }
    }

    @Test
    public void aPlainFilterTokenIsNotMistakenForTheIndexedForm() {
        MacroFilters.rejectIndexedFilterToken("filter");
        MacroFilters.rejectIndexedFilterToken("filtered_thing");
        MacroFilters.rejectIndexedFilterToken(null);
    }

    @Test
    public void tooManyFiltersIsRejected() {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i <= MacroFilters.MAX_FILTERS; i++) {
            options.append(" volume>=").append(i);
        }
        try {
            MacroFilters.parse(options.toString());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains(Integer.toString(MacroFilters.MAX_FILTERS)));
        }
    }

    @Test
    public void formatRoundTripsThroughParse() {
        List<MorphPredicate> original = MacroFilters.parse("sphericity>=0.6 volume<=500");
        String formatted = MacroFilters.format(original);
        List<MorphPredicate> reparsed = MacroFilters.parse(formatted);

        assertEquals(original.size(), reparsed.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).featureName, reparsed.get(i).featureName);
            assertEquals(original.get(i).op, reparsed.get(i).op);
            assertEquals(original.get(i).value, reparsed.get(i).value, 0.0);
        }
    }

    @Test
    public void formatHandlesEmptyAndNullLists() {
        assertEquals("", MacroFilters.format(null));
        assertEquals("", MacroFilters.format(new ArrayList<MorphPredicate>()));
    }

    @Test
    public void emptyFeatureSetFallsBackToTheBaseFeatures() {
        List<MorphPredicate> filters =
                MacroFilters.parse("sphericity>=0.6", new ArrayList<String>());
        assertEquals(1, filters.size());
    }

    @Test
    public void variantFeaturesParseOnceSupplied() {
        List<MorphPredicate> filters =
                MacroFilters.parse("skeleton_branches>=3", "skeleton_branches", "sphericity");
        assertEquals(1, filters.size());
        assertEquals("skeleton_branches", filters.get(0).featureName);
    }

    @Test
    public void nullAndEmptyOptionsGiveNoFilters() {
        assertTrue(MacroFilters.parse(null).isEmpty());
        assertTrue(MacroFilters.parse("").isEmpty());
        assertTrue(MacroFilters.parse("threshold=40 min=10").isEmpty());
    }
}
