package sc.fiji.oc3d.core.api;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import sc.fiji.oc3d.core.label.Connectivity;
import sc.fiji.oc3d.core.label.LabelParameters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OC3DParametersTest {

    @Test
    public void defaultsMatchLabelParameters() {
        OC3DParameters parameters = OC3DParameters.builder().build();
        LabelParameters defaults = new LabelParameters();
        assertEquals(defaults.threshold(), parameters.labelParameters().threshold(), 0.0);
        assertEquals(defaults.minSize(), parameters.labelParameters().minSize());
        assertEquals(defaults.maxSize(), parameters.labelParameters().maxSize());
        assertEquals(Connectivity.TWENTY_SIX, parameters.labelParameters().connectivity());
        assertTrue(parameters.morphPredicates().isEmpty());
        assertNull(parameters.intensityImage());
        assertSame(WarningSink.NONE, parameters.warningSink());
    }

    @Test
    public void shortcutsWriteThroughToLabelParameters() {
        OC3DParameters parameters = OC3DParameters.builder()
                .threshold(42)
                .minSize(5)
                .maxSize(500)
                .excludeOnEdges(true)
                .build();
        LabelParameters label = parameters.labelParameters();
        assertEquals(42.0, label.threshold(), 0.0);
        assertEquals(5L, label.minSize());
        assertEquals(500L, label.maxSize());
        assertTrue(label.excludeOnEdges());
    }

    @Test
    public void labelParametersAreCopiedInAndOut() {
        LabelParameters source = new LabelParameters().threshold(10);
        OC3DParameters parameters = OC3DParameters.builder().labelParameters(source).build();

        source.threshold(999);
        assertEquals("the builder must snapshot, not alias",
                10.0, parameters.labelParameters().threshold(), 0.0);

        parameters.labelParameters().threshold(888);
        assertEquals("the accessor must hand out a copy",
                10.0, parameters.labelParameters().threshold(), 0.0);
    }

    @Test
    public void filterListIsImmutableAndDetachedFromTheBuilder() {
        List<MorphPredicate> source = new ArrayList<MorphPredicate>();
        source.add(MorphPredicate.parse("volume>=10"));
        OC3DParameters parameters = OC3DParameters.builder().addFilters(source).build();

        source.clear();
        assertEquals(1, parameters.morphPredicates().size());

        try {
            parameters.morphPredicates().add(MorphPredicate.parse("volume<=20"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, parameters.morphPredicates().size());
        }
    }

    @Test
    public void filtersParseFromACommaSeparatedString() {
        OC3DParameters parameters = OC3DParameters.builder()
                .addFilters("sphericity>=0.6,volume>=100")
                .build();
        assertEquals(2, parameters.morphPredicates().size());
        assertEquals("sphericity", parameters.morphPredicates().get(0).featureName);
        assertEquals("volume", parameters.morphPredicates().get(1).featureName);
    }

    @Test
    public void nullFiltersAndSinkAreTolerated() {
        OC3DParameters parameters = OC3DParameters.builder()
                .addFilter(null)
                .addFilters((List<MorphPredicate>) null)
                .warningSink(null)
                .build();
        assertTrue(parameters.morphPredicates().isEmpty());
        assertSame(WarningSink.NONE, parameters.warningSink());
    }

    @Test
    public void toBuilderRoundTrips() {
        OC3DParameters original = OC3DParameters.builder()
                .threshold(7)
                .minSize(3)
                .excludeOnEdges(true)
                .addFilters("elongation>=2")
                .build();
        OC3DParameters copy = original.toBuilder().build();

        assertEquals(7.0, copy.labelParameters().threshold(), 0.0);
        assertEquals(3L, copy.labelParameters().minSize());
        assertTrue(copy.labelParameters().excludeOnEdges());
        assertEquals(1, copy.morphPredicates().size());
        assertEquals("elongation>=2.0", copy.morphPredicates().get(0).format());
    }

    @Test
    public void clearFiltersEmptiesTheBuilder() {
        OC3DParameters parameters = OC3DParameters.builder()
                .addFilters("volume>=1")
                .clearFilters()
                .build();
        assertTrue(parameters.morphPredicates().isEmpty());
    }

    @Test
    public void nullLabelParametersIsRejected() {
        try {
            OC3DParameters.builder().labelParameters(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("labelParameters"));
        }
    }

    @Test
    public void warningSinkNoneDiscards() {
        WarningSink.NONE.warn("nothing should happen");
    }

    @Test
    public void toStringNamesTheFilters() {
        String text = OC3DParameters.builder().addFilters("volume>=10").build().toString();
        assertTrue(text, text.contains("volume>=10.0"));
        assertTrue(text, text.contains("redirect=none"));
        assertFalse(text, text.contains("@"));
    }
}
