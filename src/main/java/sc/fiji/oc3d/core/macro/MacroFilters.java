package sc.fiji.oc3d.core.macro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import sc.fiji.oc3d.core.api.MorphPredicate;

/**
 * Reads morphology filters written directly into a macro options string, as in
 * {@code run("3D Objects Counter+", "threshold=40 sphericity>=0.6 volume>=100")}.
 *
 * <p>Direct predicates rather than indexed {@code filter1=sphericity>=0.6}
 * options: the indexed form was replaced because it is unreadable in a recorded
 * macro and because renumbering it when a filter is removed is a source of
 * silent mistakes. {@link #rejectIndexedFilterToken} still recognises the old
 * form and says so, so a user with an old macro gets an explanation rather than
 * a filter that quietly does nothing.
 */
public final class MacroFilters {

    /**
     * Cap on how many direct predicates one options string may carry.
     *
     * <p>A bound exists because macro options are also a place where a
     * programmatic loop can go wrong, and 64 filters on one run is already far
     * past anything a person writes by hand.
     */
    public static final int MAX_FILTERS = 64;

    private MacroFilters() {
        // Utility class.
    }

    /**
     * Parses every direct predicate in {@code options}.
     *
     * @param options  the macro options string
     * @param features feature names this variant recognises; matched
     *                 longest-first so {@code volume_calibrated>=1} is not read
     *                 as {@code volume} followed by junk
     * @return the predicates, in the order they appear
     * @throws IllegalArgumentException for a token that looks like a predicate
     *         but names an unknown feature or uses {@code =} instead of a
     *         comparison, or when more than {@link #MAX_FILTERS} are present
     */
    public static List<MorphPredicate> parse(String options, Collection<String> features) {
        List<String> ordered = longestFirst(features);
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        List<String> tokens = MacroOptions.tokens(options);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            rejectIndexedFilterToken(token);
            MorphPredicate predicate = predicateFromToken(token, ordered);
            if (predicate == null) continue;
            if (predicates.size() >= MAX_FILTERS) {
                throw new IllegalArgumentException("Too many direct filter predicates in macro options "
                        + "(maximum " + MAX_FILTERS + ").");
            }
            predicates.add(predicate);
        }
        return predicates;
    }

    /** Parses against {@link MorphPredicate#BASE_FEATURES} only. */
    public static List<MorphPredicate> parse(String options) {
        return parse(options, MorphPredicate.BASE_FEATURES);
    }

    /**
     * Formats predicates back into macro tokens, space-separated.
     *
     * <p>Round-trips through {@link #parse} for any predicate whose feature is
     * in the supplied set.
     */
    public static String format(List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            MorphPredicate predicate = predicates.get(i);
            if (predicate == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(predicate.format());
        }
        return sb.toString();
    }

    /**
     * Explains the removed {@code filter1=...} syntax instead of ignoring it.
     *
     * @throws IllegalArgumentException if {@code token} uses the indexed form
     */
    public static void rejectIndexedFilterToken(String token) {
        if (token == null || !token.startsWith("filter")) return;
        int i = "filter".length();
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            i++;
        }
        if (i > "filter".length() && i < token.length() && token.charAt(i) == '=') {
            throw new IllegalArgumentException("Macro option '" + token.substring(0, i)
                    + "' is no longer supported; use direct filter syntax such as "
                    + "'sphericity>=0.6'.");
        }
    }

    private static MorphPredicate predicateFromToken(String token, List<String> features) {
        if (token == null || token.isEmpty()) return null;
        // A bracketed value can legitimately contain < or >; it is never a filter.
        if (token.indexOf('[') >= 0) return null;
        for (int i = 0; i < features.size(); i++) {
            String feature = features.get(i);
            if (!token.startsWith(feature)) continue;
            String suffix = token.substring(feature.length());
            if (suffix.startsWith(">=") || suffix.startsWith("<=")
                    || suffix.startsWith(">") || suffix.startsWith("<")) {
                return parsePredicate(token);
            }
            if (suffix.startsWith("=")) {
                throw new IllegalArgumentException("Macro filter '" + token
                        + "' is invalid; use feature>=value, feature<=value, "
                        + "feature>value, or feature<value.");
            }
        }
        if (looksLikePredicate(token)) {
            throw new IllegalArgumentException("Unknown macro filter feature in '" + token
                    + "'. Supported features: " + join(features) + ".");
        }
        return null;
    }

    private static boolean looksLikePredicate(String token) {
        if (token == null) return false;
        return token.indexOf('>') >= 0 || token.indexOf('<') >= 0;
    }

    private static MorphPredicate parsePredicate(String token) {
        try {
            return MorphPredicate.parse(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Macro option '" + token
                    + "' has invalid morph predicate (" + token + "='" + token + "'): "
                    + e.getMessage(), e);
        }
    }

    /**
     * Orders feature names longest-first so a prefix never shadows a longer
     * name. Callers therefore do not have to get their catalogue's order right.
     */
    private static List<String> longestFirst(Collection<String> features) {
        Set<String> unique = new LinkedHashSet<String>();
        if (features != null) {
            for (String feature : features) {
                if (feature == null) continue;
                String trimmed = feature.trim();
                if (!trimmed.isEmpty()) unique.add(trimmed);
            }
        }
        if (unique.isEmpty()) {
            unique.addAll(MorphPredicate.BASE_FEATURES);
        }
        List<String> ordered = new ArrayList<String>(unique);
        Collections.sort(ordered, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                if (left.length() != right.length()) return right.length() - left.length();
                return left.compareTo(right);
            }
        });
        return ordered;
    }

    private static String join(List<String> values) {
        List<String> alphabetical = new ArrayList<String>(values);
        Collections.sort(alphabetical);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alphabetical.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(alphabetical.get(i));
        }
        return sb.toString();
    }

    /** Convenience for a varargs feature list. */
    public static List<MorphPredicate> parse(String options, String... features) {
        return parse(options, Arrays.asList(features));
    }
}
