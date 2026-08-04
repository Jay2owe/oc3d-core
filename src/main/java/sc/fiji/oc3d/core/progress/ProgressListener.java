package sc.fiji.oc3d.core.progress;

/**
 * Minimal progress sink for long-running core operations.
 *
 * <p>The core does not call {@code IJ.showStatus} or {@code IJ.showProgress}
 * itself. Those are presentation and they are not headless-safe; a plugin that
 * wants a status bar adapts this interface to one. A batch runner or a unit test
 * uses {@link #NONE} and the core stays silent.
 */
public interface ProgressListener {

    /** Discards everything. Use where no feedback is wanted. */
    ProgressListener NONE = new ProgressListener() {
        @Override
        public void progress(String stage, long done, long total) {
            // Intentionally silent.
        }
    };

    /**
     * @param stage short human-readable phase name, e.g. {@code "Finding structures"}
     * @param done units completed so far
     * @param total units in this stage, or {@code 0} when unknown
     */
    void progress(String stage, long done, long total);
}
