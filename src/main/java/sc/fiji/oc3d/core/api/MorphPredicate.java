package sc.fiji.oc3d.core.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A single morphology filter: {@code feature OP value}, where OP is one of
 * {@code >=}, {@code <=}, {@code >}, {@code <}.
 *
 * <p>Base feature names, shared by every variant in the family:
 * {@code volume}, {@code volume_calibrated}, {@code surface_area},
 * {@code sphericity}, {@code elongation}, {@code compactness},
 * {@code mean_intensity}, {@code max_intensity}, {@code feret_diameter_max}.
 *
 * <p><b>Unknown feature names always match.</b> That is deliberate and it is the
 * shipped behaviour: a macro written against a newer build, or against a variant
 * with extra measurements, must not silently discard every object when run
 * against a build that does not compute that feature. The caller is expected to
 * report it through a {@link WarningSink}; use {@link #isSupportedFeature} to
 * detect the case rather than inferring it from an empty result.
 *
 * <p>A variant that computes extra features registers their names with
 * {@link #registerFeatures} - typically from a static initialiser in its own
 * feature catalogue - so those predicates start filtering instead of passing
 * through. The registry is process-wide and additive; it is never cleared,
 * because a predicate that stopped filtering halfway through a batch would
 * change results without changing inputs. Each plugin shades its own private
 * copy of this class, so one plugin's registrations cannot leak into another's.
 */
public final class MorphPredicate {

    public enum Operator {
        GE(">="),
        LE("<="),
        GT(">"),
        LT("<");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /**
         * @param symbol one of {@code >=}, {@code <=}, {@code >}, {@code <}
         * @return the matching operator
         * @throws IllegalArgumentException naming what was given
         */
        public static Operator fromSymbol(String symbol) {
            if (">=".equals(symbol)) return GE;
            if ("<=".equals(symbol)) return LE;
            if (">".equals(symbol)) return GT;
            if ("<".equals(symbol)) return LT;
            throw new IllegalArgumentException(
                    "operator must be one of >=, <=, >, < (operator='" + symbol + "').");
        }

        /** @return true if {@code symbol} names an operator, without throwing */
        public static boolean isOperator(String symbol) {
            return ">=".equals(symbol) || "<=".equals(symbol)
                    || ">".equals(symbol) || "<".equals(symbol);
        }
    }

    /** Feature names every variant computes. */
    public static final List<String> BASE_FEATURES = Collections.unmodifiableList(Arrays.asList(
            "volume",
            "volume_calibrated",
            "surface_area",
            "sphericity",
            "elongation",
            "compactness",
            "mean_intensity",
            "max_intensity",
            "feret_diameter_max"));

    private static final Set<String> SUPPORTED_FEATURES =
            Collections.synchronizedSet(new LinkedHashSet<String>(BASE_FEATURES));

    public final String featureName;
    public final Operator op;
    public final double value;

    public MorphPredicate(String featureName, Operator op, double value) {
        if (featureName == null) {
            throw new IllegalArgumentException("Morph predicate featureName must not be null (featureName=null).");
        }
        if (featureName.trim().isEmpty()) {
            throw new IllegalArgumentException("Morph predicate featureName must not be blank (featureName='"
                    + featureName + "').");
        }
        if (op == null) {
            throw new IllegalArgumentException("Morph predicate op must not be null (op=null).");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Morph predicate value must be finite (value="
                    + value + ").");
        }
        this.featureName = featureName.trim();
        this.op = op;
        this.value = value;
    }

    /**
     * Adds feature names a variant computes beyond {@link #BASE_FEATURES}.
     *
     * <p>Additive and idempotent. Null and blank entries are ignored rather than
     * rejected, so a catalogue with a gap in it registers the rest.
     */
    public static void registerFeatures(Collection<String> names) {
        if (names == null) return;
        for (String name : names) {
            if (name == null) continue;
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) SUPPORTED_FEATURES.add(trimmed);
        }
    }

    /** @return every feature name that currently filters, base plus registered */
    public static Set<String> supportedFeatures() {
        synchronized (SUPPORTED_FEATURES) {
            return Collections.unmodifiableSet(new LinkedHashSet<String>(SUPPORTED_FEATURES));
        }
    }

    /**
     * @return false for a name that would make {@link #matches} pass everything
     *         through - the caller should warn rather than filter
     */
    public static boolean isSupportedFeature(String name) {
        return name != null && SUPPORTED_FEATURES.contains(name.trim());
    }

    /**
     * @param observed the object's value for {@link #featureName}
     * @return true if the object survives this filter; also true for an
     *         unsupported feature name, and false for a non-finite observation
     */
    public boolean matches(double observed) {
        if (!SUPPORTED_FEATURES.contains(featureName)) return true;
        if (!Double.isFinite(observed)) return false;
        if (op == Operator.GE) return observed >= value;
        if (op == Operator.LE) return observed <= value;
        if (op == Operator.GT) return observed > value;
        if (op == Operator.LT) return observed < value;
        return false;
    }

    /** Round-trips through {@link #parse}. */
    public String format() {
        return featureName + op.symbol() + Double.toString(value);
    }

    /**
     * Parses a single predicate string such as {@code "sphericity>=0.6"}.
     * Whitespace around the feature, operator, or value is allowed for Java
     * callers, but a macro filter token must still be one whitespace-free token
     * because macro options are split on whitespace.
     */
    public static MorphPredicate parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Morph predicate text must not be null (text=null).");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Morph predicate text must not be blank (text='"
                    + text + "').");
        }
        String[] operators = {">=", "<=", ">", "<"};
        Operator[] values = {Operator.GE, Operator.LE, Operator.GT, Operator.LT};
        for (int i = 0; i < operators.length; i++) {
            int at = trimmed.indexOf(operators[i]);
            if (at > 0) {
                String feature = trimmed.substring(0, at).trim();
                String rawValue = trimmed.substring(at + operators[i].length()).trim();
                try {
                    double parsedValue = Double.parseDouble(rawValue);
                    try {
                        return new MorphPredicate(feature, values[i], parsedValue);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid morph predicate (text='"
                                + text + "', featureName='" + feature + "', operator='"
                                + operators[i] + "', value='" + rawValue + "'): "
                                + e.getMessage(), e);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid morph predicate value (text='"
                            + text + "', value='" + rawValue + "').", e);
                }
            }
        }
        throw new IllegalArgumentException("Invalid morph predicate (text='" + text
                + "'; expected feature>=value, feature<=value, feature>value, or feature<value).");
    }

    /** Parses a comma-separated predicate list. Blank entries are skipped. */
    public static List<MorphPredicate> parseList(String decoded) {
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        if (decoded == null || decoded.trim().isEmpty()) return predicates;
        String[] parts = decoded.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (!part.isEmpty()) {
                predicates.add(parse(part));
            }
        }
        return predicates;
    }

    /** Formats a list as a comma-separated string parseable by {@link #parseList}. */
    public static String formatList(List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(predicates.get(i).format());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
