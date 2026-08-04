package sc.fiji.oc3d.core.api;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sc.fiji.oc3d.core.label.LabelParameters;

/**
 * Everything one run of the shared pipeline needs, minus the engine's own
 * settings.
 *
 * <p>Detection settings are <b>not</b> re-declared here - they live on
 * {@link LabelParameters}, which is what a {@link sc.fiji.oc3d.core.spi.LabelEngine}
 * consumes. Duplicating {@code threshold} / {@code minSize} / {@code maxSize}
 * onto a second parameter object is how the two copies drift apart, so this
 * class holds a {@code LabelParameters} instead.
 *
 * <p>Engine-specific settings (a StarDist model path, a watershed seed radius,
 * a variant's optional measurement groups) belong on the variant's own
 * parameter type, not here. Widening this class to fit one engine is what
 * couples all of them.
 *
 * <p>Immutable. Build with {@link #builder()}.
 */
public final class OC3DParameters {

    private final LabelParameters labelParameters;
    private final List<MorphPredicate> morphPredicates;
    private final ImagePlus intensityImage;
    private final WarningSink warningSink;

    private OC3DParameters(Builder builder) {
        this.labelParameters = builder.labelParameters.copy();
        this.morphPredicates = immutableCopy(builder.morphPredicates);
        this.intensityImage = builder.intensityImage;
        this.warningSink = builder.warningSink == null ? WarningSink.NONE : builder.warningSink;
    }

    /** @return an independent copy of the detection settings */
    public LabelParameters labelParameters() {
        return labelParameters.copy();
    }

    /** Morphology filters; an object must pass <em>all</em> of them to survive. */
    public List<MorphPredicate> morphPredicates() {
        return morphPredicates;
    }

    /**
     * Optional intensity-measurement source, the "redirect" image.
     *
     * <p>{@code null} means measure intensities on the image that was
     * segmented. When present it must have the same width, height and slice
     * count; the measurement pass rejects a mismatch rather than reading past
     * the end of a slice.
     */
    public ImagePlus intensityImage() {
        return intensityImage;
    }

    /** Never null. @see WarningSink */
    public WarningSink warningSink() {
        return warningSink;
    }

    /** @return a builder pre-loaded with this bundle's values */
    public Builder toBuilder() {
        return new Builder()
                .labelParameters(labelParameters)
                .addFilters(morphPredicates)
                .intensityImage(intensityImage)
                .warningSink(warningSink);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "OC3DParameters[" + labelParameters
                + ", filters=" + MorphPredicate.formatList(morphPredicates)
                + ", redirect=" + (intensityImage == null ? "none" : intensityImage.getTitle())
                + "]";
    }

    private static List<MorphPredicate> immutableCopy(List<MorphPredicate> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<MorphPredicate>(source));
    }

    /** Fluent builder. Defaults match {@link LabelParameters} with no filters. */
    public static final class Builder {

        private LabelParameters labelParameters = new LabelParameters();
        private final List<MorphPredicate> morphPredicates = new ArrayList<MorphPredicate>();
        private ImagePlus intensityImage;
        private WarningSink warningSink = WarningSink.NONE;

        public Builder labelParameters(LabelParameters value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "labelParameters must not be null (labelParameters=null).");
            }
            this.labelParameters = value.copy();
            return this;
        }

        /** Shortcut for {@code labelParameters().threshold(value)}. */
        public Builder threshold(double value) {
            labelParameters.threshold(value);
            return this;
        }

        /** Shortcut for {@code labelParameters().minSize(value)}. */
        public Builder minSize(long value) {
            labelParameters.minSize(value);
            return this;
        }

        /** Shortcut for {@code labelParameters().maxSize(value)}. */
        public Builder maxSize(long value) {
            labelParameters.maxSize(value);
            return this;
        }

        /** Shortcut for {@code labelParameters().excludeOnEdges(value)}. */
        public Builder excludeOnEdges(boolean value) {
            labelParameters.excludeOnEdges(value);
            return this;
        }

        public Builder addFilter(MorphPredicate predicate) {
            if (predicate != null) morphPredicates.add(predicate);
            return this;
        }

        public Builder addFilters(List<MorphPredicate> predicates) {
            if (predicates != null) {
                for (MorphPredicate predicate : predicates) {
                    addFilter(predicate);
                }
            }
            return this;
        }

        /** Parses and adds a comma-separated predicate list, e.g. {@code "sphericity>=0.6,volume>=100"}. */
        public Builder addFilters(String commaSeparated) {
            return addFilters(MorphPredicate.parseList(commaSeparated));
        }

        public Builder clearFilters() {
            morphPredicates.clear();
            return this;
        }

        /** @param value redirect image, or null to measure on the segmented image */
        public Builder intensityImage(ImagePlus value) {
            this.intensityImage = value;
            return this;
        }

        public Builder warningSink(WarningSink value) {
            this.warningSink = value == null ? WarningSink.NONE : value;
            return this;
        }

        public OC3DParameters build() {
            return new OC3DParameters(this);
        }
    }
}
