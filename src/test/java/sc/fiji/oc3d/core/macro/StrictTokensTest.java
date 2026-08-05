package sc.fiji.oc3d.core.macro;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The strict tokeniser promoted from Volumetric Colocalization.
 *
 * <p>Each rejection below is a string the lenient {@link MacroOptions#tokens}
 * accepts and mis-parses. Silently mis-parsing a macro option is worse than
 * refusing it: the run proceeds on the wrong settings and the output looks
 * ordinary.
 */
public class StrictTokensTest {

    private static String rejectionFor(String options) {
        try {
            MacroOptions.strictTokens(options);
            fail("expected a rejection for: " + options);
            return null;
        } catch (IllegalArgumentException expected) {
            return expected.getMessage();
        }
    }

    @Test
    public void wellFormedOptionsTokeniseTheSameAsTheLenientVersion() {
        for (String options : new String[]{
                "",
                "a=1 b=2",
                "redirect=[my image.tif] threshold=30",
                "  spaced   out  ",
                "flag other=[with space]"}) {
            assertEquals("differs for: " + options,
                    MacroOptions.tokens(options),
                    MacroOptions.strictTokens(options));
        }
    }

    @Test
    public void bracketedValuesStayOneToken() {
        List<String> tokens = MacroOptions.strictTokens("redirect=[my image.tif] n=3");
        assertEquals(Arrays.asList("redirect=[my image.tif]", "n=3"), tokens);
    }

    @Test
    public void nestedBracketsAreBalancedNotClosedEarly() {
        assertEquals(Arrays.asList("a=[x [y] z]"),
                MacroOptions.strictTokens("a=[x [y] z]"));
    }

    @Test
    public void anUnclosedBracketIsRefused() {
        // The lenient version swallows the whole rest of the string into one
        // token and carries on.
        assertTrue(rejectionFor("a=[unterminated b=2").contains("Unclosed"));
    }

    @Test
    public void aStrayClosingBracketIsRefused() {
        assertTrue(rejectionFor("a=1] b=2").contains("Unexpected closing bracket"));
    }

    @Test
    public void aLineBreakInsideAValueIsRefused() {
        // A macro cannot reproduce this on replay, so accepting it produces a
        // recording that does not run.
        assertTrue(rejectionFor("a=[one\ntwo]").contains("Line breaks"));
    }

    @Test
    public void nullIsEmptyRatherThanAnError() {
        assertTrue(MacroOptions.strictTokens(null).isEmpty());
    }

    @Test
    public void theLenientVersionStillAcceptsWhatItAlwaysDid() {
        // Pins the deliberate split: migrating plugins are not broken by this
        // change landing in shared code.
        assertEquals(1, MacroOptions.tokens("a=[unterminated").size());
        assertEquals(2, MacroOptions.tokens("a=1] b=2").size());
    }
}
