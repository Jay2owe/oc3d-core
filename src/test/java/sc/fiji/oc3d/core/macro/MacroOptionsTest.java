package sc.fiji.oc3d.core.macro;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MacroOptionsTest {

    @Test
    public void readsPlainValues() {
        String options = "threshold=40 min=10 max=Infinity";
        assertEquals("40", MacroOptions.value(options, "threshold", null));
        assertEquals("10", MacroOptions.value(options, "min", null));
        assertEquals("Infinity", MacroOptions.value(options, "max", null));
        assertEquals("fallback", MacroOptions.value(options, "absent", "fallback"));
    }

    @Test
    public void keyLookupIsTokenAwareNotSubstring() {
        // "min" appears inside "hide_min_something"; only the real token counts.
        assertNull(MacroOptions.value("hide_min=3", "min", null));
        assertEquals("7", MacroOptions.value("hide_min=3 min=7", "min", null));
    }

    @Test
    public void keysInsideBracketsAreNotOptions() {
        String options = "redirect=[a min=99 title] min=4";
        assertEquals("4", MacroOptions.value(options, "min", null));
        assertEquals("a min=99 title", MacroOptions.bracketed(options, "redirect", null));
    }

    @Test
    public void bracketedValuesNest() {
        assertEquals("outer [inner] tail",
                MacroOptions.bracketed("redirect=[outer [inner] tail] min=1", "redirect", null));
    }

    @Test
    public void unclosedBracketFallsBackToTheDefault() {
        assertEquals("none", MacroOptions.bracketed("redirect=[never closed", "redirect", "none"));
    }

    @Test
    public void plainValueLookupDeclinesABracketedValue() {
        assertNull("a bracketed value must be read with bracketed(), not value()",
                MacroOptions.value("redirect=[my image]", "redirect", null));
    }

    @Test
    public void bracketedOrValueAcceptsEitherForm() {
        assertEquals("C:/models/my model.zip",
                MacroOptions.bracketedOrValue("model=[C:/models/my model.zip]", "model", null));
        assertEquals("C:/models/plain.zip",
                MacroOptions.bracketedOrValue("model=C:/models/plain.zip", "model", null));
        assertEquals("fallback",
                MacroOptions.bracketedOrValue("other=1", "model", "fallback"));
    }

    @Test
    public void flagsMustBeWholeTokens() {
        assertTrue(MacroOptions.hasFlag("min=1 exclude_edges hide_stats", "exclude_edges"));
        assertTrue(MacroOptions.hasFlag("exclude_edges", "exclude_edges"));
        assertFalse("a key with a value is not a flag",
                MacroOptions.hasFlag("exclude_edges=true", "exclude_edges"));
        assertFalse("a longer token is a different option",
                MacroOptions.hasFlag("exclude_edges2", "exclude_edges"));
        assertFalse(MacroOptions.hasFlag("hide_stats", "stats"));
    }

    @Test
    public void tokensKeepBracketedRegionsTogether() {
        List<String> tokens = MacroOptions.tokens("min=1 redirect=[my image.tif] sphericity>=0.6");
        assertEquals(3, tokens.size());
        assertEquals("min=1", tokens.get(0));
        assertEquals("redirect=[my image.tif]", tokens.get(1));
        assertEquals("sphericity>=0.6", tokens.get(2));
    }

    @Test
    public void tokensHandlesNullAndBlank() {
        assertTrue(MacroOptions.tokens(null).isEmpty());
        assertTrue(MacroOptions.tokens("   ").isEmpty());
    }

    @Test
    public void intOptionClampsInsteadOfOverflowing() {
        assertEquals(0, MacroOptions.parseIntOption("-5", 99, "min"));
        assertEquals(0, MacroOptions.parseIntOption("0", 99, "min"));
        assertEquals(7, MacroOptions.parseIntOption("7", 99, "min"));
        assertEquals(7, MacroOptions.parseIntOption("7.0", 99, "min"));
        assertEquals(8, MacroOptions.parseIntOption("7.6", 99, "min"));
        assertEquals(Integer.MAX_VALUE, MacroOptions.parseIntOption("1e30", 99, "min"));
        assertEquals(99, MacroOptions.parseIntOption(null, 99, "min"));
    }

    @Test
    public void intOptionRejectsBlankAndNonNumeric() {
        assertParseFails("", "min");
        assertParseFails("   ", "min");
        assertParseFails("ten", "min");
        assertParseFails("Infinity", "min");
    }

    @Test
    public void doubleOptionKeepsPrecisionAndRejectsNaN() {
        assertEquals(0.35, MacroOptions.parseDoubleOption("0.35", 0.5, "probability"), 0.0);
        assertEquals(0.5, MacroOptions.parseDoubleOption(null, 0.5, "probability"), 0.0);
        assertEquals(0.5, MacroOptions.parseDoubleOption("  ", 0.5, "probability"), 0.0);
        try {
            MacroOptions.parseDoubleOption("NaN", 0.5, "probability");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("probability"));
        }
    }

    @Test
    public void maxSizeRoundTripsThroughInfinity() {
        assertEquals(Integer.MAX_VALUE, MacroOptions.parseMaxSize(null));
        assertEquals(Integer.MAX_VALUE, MacroOptions.parseMaxSize("Infinity"));
        assertEquals(Integer.MAX_VALUE, MacroOptions.parseMaxSize("inf"));
        assertEquals(Integer.MAX_VALUE, MacroOptions.parseMaxSize("INFINITY"));
        assertEquals(500, MacroOptions.parseMaxSize("500"));
        assertEquals("Infinity", MacroOptions.formatMaxSize(Integer.MAX_VALUE));
        assertEquals("500", MacroOptions.formatMaxSize(500));
        assertEquals(Integer.MAX_VALUE,
                MacroOptions.parseMaxSize(MacroOptions.formatMaxSize(Integer.MAX_VALUE)));
    }

    @Test
    public void unsafeBracketValuesAreRejectedWhenWriting() {
        assertTrue(MacroOptions.isSafeBracketedValue("my image.tif"));
        assertFalse(MacroOptions.isSafeBracketedValue("has ] bracket"));
        assertFalse(MacroOptions.isSafeBracketedValue("has [ bracket"));
        assertFalse(MacroOptions.isSafeBracketedValue("has \" quote"));
        assertFalse(MacroOptions.isSafeBracketedValue("has \\ backslash"));
        assertFalse(MacroOptions.isSafeBracketedValue("has \n newline"));
        assertFalse(MacroOptions.isSafeBracketedValue(null));

        assertEquals("fine", MacroOptions.requireSafeBracketedValue("fine", "Redirect image title"));
        try {
            MacroOptions.requireSafeBracketedValue("bad ] title", "Redirect image title");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Redirect image title"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("bad ] title"));
        }
    }

    @Test
    public void nullOptionsAreTolerated() {
        assertNull(MacroOptions.value(null, "min", null));
        assertNull(MacroOptions.bracketed(null, "redirect", null));
        assertFalse(MacroOptions.hasFlag(null, "exclude_edges"));
    }

    private static void assertParseFails(String token, String optionName) {
        try {
            MacroOptions.parseIntOption(token, 0, optionName);
            fail("expected IllegalArgumentException for '" + token + "'");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(optionName));
        }
    }
}
