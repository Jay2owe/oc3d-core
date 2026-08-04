package sc.fiji.oc3d.core.api;

/**
 * Where the core sends non-fatal messages: an unrecognised filter feature, a
 * measurement that could not be computed, a map that was skipped.
 *
 * <p>It exists so the core never decides <em>how</em> a message is shown. A GUI
 * plugin points this at a status line, a batch runner appends to a log file, a
 * unit test collects into a list, and a headless run uses {@link #NONE}. Calling
 * {@code IJ.error} from inside the core would block a background thread and fail
 * headless, which is why it is not done.
 */
public interface WarningSink {

    /** Discards everything. */
    WarningSink NONE = new WarningSink() {
        @Override
        public void warn(String message) {
            // Intentionally silent.
        }
    };

    /**
     * @param message human-readable, already naming the offending value; never
     *                null
     */
    void warn(String message);
}
