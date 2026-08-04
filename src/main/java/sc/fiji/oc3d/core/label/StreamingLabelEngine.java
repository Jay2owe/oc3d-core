package sc.fiji.oc3d.core.label;

import ij.ImagePlus;

import sc.fiji.oc3d.core.progress.ProgressListener;
import sc.fiji.oc3d.core.spi.LabelEngine;

/**
 * {@link LabelEngine} backed by {@link StreamingLabeller} - the threshold-and-
 * connect engine used by 3D Objects Counter+.
 *
 * <p>Stateless, so a single instance can be shared.
 */
public final class StreamingLabelEngine implements LabelEngine {

    /** Shared instance; the class holds no state. */
    public static final StreamingLabelEngine INSTANCE = new StreamingLabelEngine();

    @Override
    public LabelResult label(ImagePlus source,
                             LabelParameters parameters,
                             ProgressListener progress) {
        return StreamingLabeller.label(source, parameters, progress);
    }

    @Override
    public String name() {
        return "streaming";
    }
}
