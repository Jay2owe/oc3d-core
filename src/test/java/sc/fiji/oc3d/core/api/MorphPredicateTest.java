package sc.fiji.oc3d.core.api;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MorphPredicateTest {

    @Test
    public void parsesEveryOperator() {
        assertEquals(MorphPredicate.Operator.GE, MorphPredicate.parse("volume>=10").op);
        assertEquals(MorphPredicate.Operator.LE, MorphPredicate.parse("volume<=10").op);
        assertEquals(MorphPredicate.Operator.GT, MorphPredicate.parse("volume>10").op);
        assertEquals(MorphPredicate.Operator.LT, MorphPredicate.parse("volume<10").op);
    }

    @Test
    public void twoCharacterOperatorsWinOverTheirPrefixes() {
        // ">" is a prefix of ">=", so a naive scan would read "volume>" then "=10".
        MorphPredicate predicate = MorphPredicate.parse("volume>=10");
        assertEquals("volume", predicate.featureName);
        assertEquals(10.0, predicate.value, 0.0);
    }

    @Test
    public void formatRoundTrips() {
        MorphPredicate original = new MorphPredicate("sphericity", MorphPredicate.Operator.LE, 0.75);
        MorphPredicate reparsed = MorphPredicate.parse(original.format());
        assertEquals(original.featureName, reparsed.featureName);
        assertEquals(original.op, reparsed.op);
        assertEquals(original.value, reparsed.value, 0.0);
    }

    @Test
    public void listRoundTripsAndSkipsBlanks() {
        List<MorphPredicate> parsed = MorphPredicate.parseList("volume>=10, ,sphericity<=0.5,");
        assertEquals(2, parsed.size());
        assertEquals("volume>=10.0,sphericity<=0.5", MorphPredicate.formatList(parsed));
    }

    @Test
    public void matchesAppliesTheOperator() {
        MorphPredicate ge = new MorphPredicate("volume", MorphPredicate.Operator.GE, 10);
        assertTrue(ge.matches(10));
        assertTrue(ge.matches(11));
        assertFalse(ge.matches(9));

        MorphPredicate gt = new MorphPredicate("volume", MorphPredicate.Operator.GT, 10);
        assertFalse(gt.matches(10));
        assertTrue(gt.matches(10.0001));
    }

    @Test
    public void nonFiniteObservationFailsASupportedFeature() {
        MorphPredicate predicate = new MorphPredicate("sphericity", MorphPredicate.Operator.GE, 0.0);
        assertFalse("NaN is not >= 0; an unmeasurable object must not pass",
                predicate.matches(Double.NaN));
        assertFalse(predicate.matches(Double.POSITIVE_INFINITY));
    }

    @Test
    public void unknownFeatureAlwaysMatches() {
        MorphPredicate predicate = new MorphPredicate("not_a_feature", MorphPredicate.Operator.GE, 999);
        assertFalse(MorphPredicate.isSupportedFeature("not_a_feature"));
        assertTrue("a macro from a newer build must not silently discard every object",
                predicate.matches(0));
        assertTrue(predicate.matches(Double.NaN));
    }

    @Test
    public void registeredFeaturesStartFiltering() {
        String feature = "morph_predicate_test_registered_feature";
        assertFalse(MorphPredicate.isSupportedFeature(feature));
        MorphPredicate predicate = new MorphPredicate(feature, MorphPredicate.Operator.GE, 5);
        assertTrue(predicate.matches(1));

        MorphPredicate.registerFeatures(Arrays.asList(feature, null, "  "));

        assertTrue(MorphPredicate.isSupportedFeature(feature));
        assertFalse(predicate.matches(1));
        assertTrue(predicate.matches(5));
        assertTrue(MorphPredicate.supportedFeatures().containsAll(MorphPredicate.BASE_FEATURES));
    }

    @Test
    public void constructorRejectsBadArguments() {
        assertRejected(null, MorphPredicate.Operator.GE, 1);
        assertRejected("  ", MorphPredicate.Operator.GE, 1);
        assertRejected("volume", null, 1);
        assertRejected("volume", MorphPredicate.Operator.GE, Double.NaN);
        assertRejected("volume", MorphPredicate.Operator.GE, Double.POSITIVE_INFINITY);
    }

    @Test
    public void parseRejectsMalformedText() {
        assertParseRejected(null);
        assertParseRejected("");
        assertParseRejected("volume");
        assertParseRejected("volume>=abc");
        assertParseRejected(">=10");
    }

    @Test
    public void operatorFromSymbolRejectsAnythingElse() {
        assertEquals(MorphPredicate.Operator.LT, MorphPredicate.Operator.fromSymbol("<"));
        assertTrue(MorphPredicate.Operator.isOperator(">="));
        assertFalse(MorphPredicate.Operator.isOperator("=="));
        try {
            MorphPredicate.Operator.fromSymbol("==");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("=="));
        }
    }

    private static void assertRejected(String feature, MorphPredicate.Operator op, double value) {
        try {
            new MorphPredicate(feature, op, value);
            fail("expected IllegalArgumentException for feature=" + feature
                    + ", op=" + op + ", value=" + value);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    private static void assertParseRejected(String text) {
        try {
            MorphPredicate.parse(text);
            fail("expected IllegalArgumentException for text='" + text + "'");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }
}
