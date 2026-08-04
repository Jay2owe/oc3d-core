package sc.fiji.oc3d.core.spi;

import ij.ImagePlus;

import sc.fiji.oc3d.core.label.LabelParameters;
import sc.fiji.oc3d.core.label.LabelResult;
import sc.fiji.oc3d.core.progress.ProgressListener;

/**
 * The one axis each 3D Objects Counter variant swaps: how voxels become objects.
 *
 * <p>Everything downstream of this - measurement, maps, filtering, tables, batch
 * running, macro recording - is shared. A variant supplies an implementation and
 * inherits the rest.
 *
 * <ul>
 *   <li>3D Objects Counter+ &rarr; {@code sc.fiji.oc3d.core.label.StreamingLabeller}</li>
 *   <li>3D Objects Counter - StarDist &rarr; StarDist per-slice detection with
 *       TrackMate z-linking</li>
 *   <li>3D Objects Counter - Labels &rarr; a pass-through that renumbers an
 *       existing label image</li>
 * </ul>
 *
 * <p><b>Contract.</b> An implementation returns a dense {@code 1..N} label image
 * and throws on bad input. It must not open a dialog, call {@code IJ.error} or
 * {@code IJ.showMessage}, or touch {@code WindowManager} - it has to run
 * headless, in batch, and on a background thread. Engine-specific settings that
 * do not fit {@link LabelParameters} belong on the implementation, not here;
 * widening this interface to fit one engine is what couples all of them.
 */
public interface LabelEngine {

    /**
     * @param source single 3D volume
     * @param parameters shared detection settings; may be {@code null} for defaults
     * @param progress progress sink; may be {@code null}
     * @return dense {@code 1..N} labelling of {@code source}
     */
    LabelResult label(ImagePlus source, LabelParameters parameters, ProgressListener progress);

    /** Short name for logs, CSV headers and macro options, e.g. {@code "streaming"}. */
    String name();
}
