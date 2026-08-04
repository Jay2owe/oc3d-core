package sc.fiji.oc3d.core.io;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which statistics columns are worth scoring against the rest of the batch, and
 * what unit the score is in.
 *
 * <p>An <b>allowlist</b>, not a denylist. Coordinates, bounding boxes, labels
 * and raw intensity are all numeric and all meaningless as a within-batch
 * z-score: "this object's X centroid is in the 90th percentile" describes where
 * it sits in the field of view, not what it is. Scoring everything numeric would
 * bury the columns that do mean something.
 *
 * <h2>The calibrated-heading problem</h2>
 *
 * Volume and surface headings carry the image's unit - {@code Volume (um^3)},
 * {@code Volume (pixel^3)} - so the heading differs between images in the same
 * batch. {@link #canonicalFeature} maps those onto the stable names
 * {@link #VOLUME} and {@link #SURFACE} so one score row covers the whole batch,
 * while the original heading stays in the per-object file.
 *
 * <p>{@link #physicalDimensionPower} is what tells the caller how to convert a
 * value to the common micrometre unit before scoring: a length is scaled by
 * {@code k}, an area by {@code k^2}, a volume by {@code k^3}. Mixing calibrated
 * and uncalibrated images in one batch without that conversion is how a batch
 * ends up with a bimodal volume distribution and no explanation.
 */
public final class ScoreFeatureCatalog {

    /** Stable name for the calibrated volume column, whatever its unit. */
    public static final String VOLUME = "Volume";
    /** Stable name for the calibrated surface column, whatever its unit. */
    public static final String SURFACE = "Surface";

    private static final Set<String> BASE_FEATURES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    VOLUME,
                    SURFACE,
                    "Nb of obj. voxels",
                    "Nb of surf. voxels",
                    "Morph_Sphericity",
                    "Morph_Compactness",
                    "Morph_Elongation",
                    "Morph_Feret3D_um")));

    private static final Set<String> FEATURES =
            Collections.synchronizedSet(new LinkedHashSet<String>(BASE_FEATURES));

    private ScoreFeatureCatalog() {}

    /**
     * Adds variant-specific scoreable headings.
     *
     * <p>Additive, as {@link sc.fiji.oc3d.core.api.MorphPredicate#registerFeatures}
     * is, and for the same reason: a column that stopped being scored partway
     * through a batch would change the file without changing the inputs.
     */
    public static void registerFeatures(Collection<String> headings) {
        if (headings == null) return;
        for (String heading : headings) {
            if (heading == null) continue;
            String trimmed = heading.trim();
            if (!trimmed.isEmpty()) FEATURES.add(trimmed);
        }
    }

    /** Every scoreable feature name, base plus registered, in registration order. */
    public static Set<String> features() {
        synchronized (FEATURES) {
            return Collections.unmodifiableSet(new LinkedHashSet<String>(FEATURES));
        }
    }

    /** @return true if {@code heading} maps onto a scoreable feature */
    public static boolean isScoreable(String heading) {
        return canonicalFeature(heading) != null;
    }

    /**
     * Maps a statistics heading onto a stable score feature name.
     *
     * @return the feature name, or {@code null} if the heading is not scored
     */
    public static String canonicalFeature(String heading) {
        if (heading == null) return null;
        if (heading.startsWith("Volume (") && heading.endsWith("^3)")) {
            return VOLUME;
        }
        if (heading.startsWith("Surface (") && heading.endsWith("^2)")) {
            return SURFACE;
        }
        return FEATURES.contains(heading) ? heading : null;
    }

    /**
     * Power of length the feature carries, for converting the raw value into
     * the common micrometre unit before scoring.
     *
     * <p>Only for values reported in the <em>image's</em> unit. A column already
     * computed in micrometres - anything whose name ends {@code _um} other than
     * Feret - returns 0, because converting it again would scale it twice.
     *
     * @return 1 for a length, 2 for an area, 3 for a volume, 0 for a count, a
     *         dimensionless ratio, or a value already in micrometres
     */
    public static int physicalDimensionPower(String feature) {
        if (VOLUME.equals(feature)) return 3;
        if (SURFACE.equals(feature)) return 2;
        if ("Morph_Feret3D_um".equals(feature)) return 1;
        return 0;
    }

    /** Unit the scored value is expressed in, for the score file's own column. */
    public static String scoringUnit(String feature) {
        int power = physicalDimensionPower(feature);
        if (power == 1) return "um";
        if (power == 2) return "um^2";
        if (power == 3) return "um^3";
        if (feature != null && feature.endsWith("_um")) return "um";
        if ("Nb of obj. voxels".equals(feature)
                || "Nb of surf. voxels".equals(feature)
                || (feature != null && feature.endsWith("Voxels"))) {
            return "voxel";
        }
        if (feature != null && (feature.contains("Branches")
                || feature.contains("Junctions")
                || feature.contains("Endpoints")
                || feature.contains("Intersections"))) {
            return "count";
        }
        return "unitless";
    }
}
