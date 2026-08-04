package sc.fiji.oc3d.core.macro;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the options string ImageJ passes to
 * {@code run("3D Objects Counter+", "threshold=40 min=10 ...")}.
 *
 * <p>Pure string handling - no ImageJ types - so a macro contract can be tested
 * without starting Fiji. Each variant keeps its own {@code parse} method that
 * turns these primitives into its own settings object; what lives here is the
 * tokenising and the number parsing, which is where the fiddly cases are and
 * where two copies would drift.
 *
 * <h2>Grammar</h2>
 *
 * Whitespace-separated tokens, in three shapes:
 *
 * <ul>
 *   <li>{@code key=value} - read with {@link #value}. Stops at whitespace.</li>
 *   <li>{@code key=[value with spaces]} - read with {@link #bracketed}.
 *       Bracket pairs nest; there is no escape for a literal unmatched
 *       {@code ]}, which is why {@link #requireSafeBracketedValue} exists.</li>
 *   <li>{@code flag} - read with {@link #hasFlag}.</li>
 * </ul>
 *
 * <h2>Why token-aware lookup</h2>
 *
 * A naive {@code indexOf("min=")} finds the {@code min=} inside
 * {@code hide_min=...}, and {@code indexOf("max")} matches inside
 * {@code max_intensity>=5}. Every lookup here requires the match to start the
 * string or follow whitespace, and skips over bracketed regions so an image
 * title containing {@code min=} in {@code redirect=[...]} cannot be mistaken for
 * an option.
 */
public final class MacroOptions {

    private MacroOptions() {
        // Utility class.
    }

    /**
     * Value of {@code key=value}, stopping at the first whitespace.
     *
     * @return {@code defaultValue} when the key is absent, or when its value is
     *         bracketed - use {@link #bracketed} for those
     */
    public static String value(String options, String key, String defaultValue) {
        if (options == null || key == null) return defaultValue;
        String marker = key + "=";
        int at = findToken(options, marker);
        if (at < 0) return defaultValue;
        int start = at + marker.length();
        if (start < options.length() && options.charAt(start) == '[') {
            return defaultValue;
        }
        int end = start;
        while (end < options.length() && !Character.isWhitespace(options.charAt(end))) {
            end++;
        }
        return options.substring(start, end);
    }

    /**
     * Value of {@code key=[bracketed content]}, spaces preserved.
     *
     * @return {@code defaultValue} when the key is absent or the brackets never
     *         close
     */
    public static String bracketed(String options, String key, String defaultValue) {
        if (options == null || key == null) return defaultValue;
        String marker = key + "=[";
        int at = findToken(options, marker);
        if (at < 0) return defaultValue;
        int start = at + marker.length();
        int depth = 1;
        for (int i = start; i < options.length(); i++) {
            char c = options.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return options.substring(start, i);
                }
            }
        }
        return defaultValue;
    }

    /**
     * Bracketed value if present, otherwise the plain value.
     *
     * <p>For options such as a file path that a user may or may not have
     * wrapped in brackets depending on whether it contained a space.
     */
    public static String bracketedOrValue(String options, String key, String defaultValue) {
        String value = bracketed(options, key, null);
        if (value != null) return value;
        return value(options, key, defaultValue);
    }

    /**
     * True when {@code flag} appears as a whole token.
     *
     * <p>{@code flag=value} and {@code flag1} both return false: a flag is
     * present or absent, and something that looks like a different option is a
     * different option.
     */
    public static boolean hasFlag(String options, String flag) {
        if (options == null || flag == null) return false;
        int at = findToken(options, flag);
        if (at < 0) return false;
        int after = at + flag.length();
        if (after < options.length()) {
            char c = options.charAt(after);
            if (c == '=' || !Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * Splits into whitespace-separated tokens, treating a bracketed region as
     * part of the token that opened it.
     *
     * <p>So {@code redirect=[my image.tif]} is one token, not two.
     */
    public static List<String> tokens(String options) {
        List<String> tokens = new ArrayList<String>();
        if (options == null || options.isEmpty()) return tokens;
        StringBuilder token = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < options.length(); i++) {
            char c = options.charAt(i);
            if (Character.isWhitespace(c) && depth == 0) {
                addToken(tokens, token);
                continue;
            }
            token.append(c);
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        addToken(tokens, token);
        return tokens;
    }

    /**
     * Rejects a value that cannot survive a round trip through a bracketed
     * macro option.
     *
     * <p>Called when <em>writing</em> options, so the failure surfaces at
     * recording time with an actionable message, rather than at replay time as a
     * mis-parsed image title. There is no escape syntax to fall back on: an
     * unmatched {@code ]} in an image title genuinely cannot be expressed.
     *
     * @return {@code value}, unchanged, when it is safe
     * @throws IllegalArgumentException naming the field and the offending value
     */
    public static String requireSafeBracketedValue(String value, String fieldName) {
        String label = fieldName == null || fieldName.trim().isEmpty()
                ? "Macro bracket value" : fieldName;
        if (value == null) {
            throw new IllegalArgumentException(label
                    + " must not be null (" + label + "=null).");
        }
        if (!isSafeBracketedValue(value)) {
            throw new IllegalArgumentException(label
                    + " cannot contain [, ], quotes, backslashes, or line breaks in macro options "
                    + "(" + label + "='" + value + "'). "
                    + "Rename the image and try again.");
        }
        return value;
    }

    /** @see #requireSafeBracketedValue */
    public static boolean isSafeBracketedValue(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[' || c == ']' || c == '"' || c == '\\' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses an integer option, clamping rather than overflowing.
     *
     * <p>Values at or below zero become {@code 0} and values at or above
     * {@link Integer#MAX_VALUE} become {@code Integer.MAX_VALUE}, because these
     * options are voxel counts and thresholds where "as small as possible" and
     * "no limit" are the intended meanings. Parsing goes through
     * {@code Double.parseDouble} so a macro that computed {@code min=10.0}
     * arithmetically still works.
     *
     * @param token       raw text, or {@code null} for the default
     * @param fallback    value when {@code token} is null
     * @param optionName  name used in error messages
     * @throws IllegalArgumentException for blank or non-numeric text
     */
    public static int parseIntOption(String token, int fallback, String optionName) {
        if (token == null) return fallback;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' must not be blank (" + optionName + "='" + token + "').");
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException("Macro option '" + optionName
                        + "' must be finite (" + optionName + "='" + token + "').");
            }
            if (parsed <= 0) return 0;
            if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.round(parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' has invalid numeric value (" + optionName + "='" + token + "').", e);
        }
    }

    /**
     * Parses a floating-point option. Unlike {@link #parseIntOption} nothing is
     * clamped - a probability or a linking distance has no natural bound the
     * parser could impose - but {@code NaN} is rejected.
     */
    public static double parseDoubleOption(String token, double fallback, String optionName) {
        if (token == null || token.trim().isEmpty()) return fallback;
        try {
            double parsed = Double.parseDouble(token.trim());
            if (Double.isNaN(parsed)) {
                throw new IllegalArgumentException("Macro option '" + optionName
                        + "' must not be NaN (" + optionName + "='" + token + "').");
            }
            return parsed;
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' must be a number (" + optionName + "='" + token + "').", nfe);
        }
    }

    /**
     * Parses a maximum-size option, accepting {@code Infinity} and {@code inf}
     * as {@link Integer#MAX_VALUE}.
     *
     * <p>The word forms exist because {@code max=Infinity} is what
     * {@link #formatMaxSize} writes, so a recorded macro round-trips.
     */
    public static int parseMaxSize(String token) {
        if (token == null) return Integer.MAX_VALUE;
        String t = token.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Macro option 'max' must not be blank (max='" + token + "').");
        }
        if ("infinity".equalsIgnoreCase(t) || "inf".equalsIgnoreCase(t)) {
            return Integer.MAX_VALUE;
        }
        return parseIntOption(t, Integer.MAX_VALUE, "max");
    }

    /** Inverse of {@link #parseMaxSize}. */
    public static String formatMaxSize(int maxSize) {
        return maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize);
    }

    /**
     * Finds {@code needle} at a position that starts the string or follows
     * whitespace, skipping bracketed regions.
     *
     * @return the index, or {@code -1}
     */
    static int findToken(String options, String needle) {
        int depth = 0;
        for (int i = 0; i <= options.length() - needle.length(); i++) {
            char c = options.charAt(i);
            if (depth == 0
                    && options.startsWith(needle, i)
                    && (i == 0 || Character.isWhitespace(options.charAt(i - 1)))) {
                return i;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        return -1;
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (token == null || token.length() == 0) return;
        tokens.add(token.toString());
        token.setLength(0);
    }
}
