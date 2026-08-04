package sc.fiji.oc3d.core.progress;

import ij.IJ;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;

/**
 * Drives the ImageJ status bar and progress bar as {@code "Step 2/5: Measuring"}.
 *
 * <p>This is the one place in the core that touches the ImageJ UI, and it is
 * here rather than in a plugin because every variant runs the same sequence of
 * steps and the wording is part of what makes them feel like one family.
 *
 * <p>It stays safe to call from anywhere:
 *
 * <ul>
 *   <li>Updates go through {@link EventQueue#invokeLater} when there is a
 *       display and run inline when there is not, so an engine on a worker
 *       thread never touches Swing off the EDT and a headless batch never
 *       queues events into a dispatch thread that will not run them.</li>
 *   <li>Every {@code IJ} call is wrapped, because {@code IJ.showStatus} throws
 *       when no ImageJ instance exists - as in a unit test.</li>
 *   <li>{@link #none()} discards everything, so a batch runner passes that and
 *       the core stays silent.</li>
 * </ul>
 *
 * <p>Also a {@link ProgressListener}, so the same instance can be handed to a
 * {@link sc.fiji.oc3d.core.spi.LabelEngine}: those reports become the detail
 * line under the current step rather than their own steps.
 */
public final class StatusBarProgress implements ProgressListener {

    private static final StatusBarProgress NONE = new StatusBarProgress(1, false);

    private final int totalSteps;
    private final boolean enabled;
    private int currentStep;

    private StatusBarProgress(int totalSteps, boolean enabled) {
        this.totalSteps = Math.max(1, totalSteps);
        this.enabled = enabled;
    }

    /** A reporter for a run of {@code totalSteps} steps. */
    public static StatusBarProgress steps(int totalSteps) {
        return new StatusBarProgress(totalSteps, true);
    }

    /** A reporter that shows nothing. */
    public static StatusBarProgress none() {
        return NONE;
    }

    /** Advances to the next step and shows its name. */
    public void step(String description) {
        if (!enabled) return;
        currentStep = Math.min(totalSteps, currentStep + 1);
        showStatus(formatStep(currentStep, totalSteps, description));
        showProgress(progressAtStepStart(currentStep, totalSteps));
    }

    /** Replaces the text of the current step without advancing the bar. */
    public void detail(String description) {
        if (!enabled) return;
        int step = currentStep <= 0 ? 1 : currentStep;
        showStatus(formatStep(step, totalSteps, description));
    }

    /** Fills the bar to the end of the current step. */
    public void finishStep() {
        if (!enabled || currentStep <= 0) return;
        showProgress(progressAtStepEnd(currentStep, totalSteps));
    }

    /** Jumps to the last step and fills the bar. */
    public void finish(String description) {
        if (!enabled) return;
        currentStep = totalSteps;
        showStatus(formatStep(totalSteps, totalSteps, description));
        showProgress(1.0);
    }

    /**
     * Shows a failure and clears the bar.
     *
     * <p>Status text only - the core never opens a dialog. Presenting the error
     * itself is the plugin's job.
     */
    public void error(String pluginName, String description) {
        if (!enabled) return;
        String prefix = pluginName == null || pluginName.trim().isEmpty()
                ? "" : pluginName.trim() + ": ";
        showStatus(prefix + safeDescription(description));
        showProgress(1.0);
    }

    /** Reports engine progress as detail under the current step. */
    @Override
    public void progress(String stage, long done, long total) {
        if (!enabled) return;
        if (total > 0L) {
            detail(safeDescription(stage) + " " + done + "/" + total);
        } else {
            detail(safeDescription(stage));
        }
    }

    /** @return {@code "Step 2/5: Measuring"} */
    public static String formatStep(int step, int totalSteps, String description) {
        int safeTotal = Math.max(1, totalSteps);
        int safeStep = Math.min(safeTotal, Math.max(1, step));
        return "Step " + safeStep + "/" + safeTotal + ": " + safeDescription(description);
    }

    /** Bar fraction as a step begins. */
    public static double progressAtStepStart(int step, int totalSteps) {
        int safeTotal = Math.max(1, totalSteps);
        int safeStep = Math.min(safeTotal, Math.max(1, step));
        return (double) (safeStep - 1) / (double) safeTotal;
    }

    /** Bar fraction as a step ends. */
    public static double progressAtStepEnd(int step, int totalSteps) {
        int safeTotal = Math.max(1, totalSteps);
        int safeStep = Math.min(safeTotal, Math.max(1, step));
        return (double) safeStep / (double) safeTotal;
    }

    private static String safeDescription(String description) {
        return description == null || description.trim().isEmpty()
                ? "Working" : description.trim();
    }

    private static void showStatus(final String text) {
        runImageJUiUpdate(new Runnable() {
            @Override public void run() {
                try {
                    IJ.showStatus(text);
                } catch (RuntimeException ignored) {
                    // Headless and test environments may not have a live ImageJ UI.
                }
            }
        });
    }

    private static void showProgress(final double progress) {
        runImageJUiUpdate(new Runnable() {
            @Override public void run() {
                try {
                    IJ.showProgress(progress);
                } catch (RuntimeException ignored) {
                    // Headless and test environments may not have a live ImageJ UI.
                }
            }
        });
    }

    private static void runImageJUiUpdate(Runnable update) {
        if (update == null) return;
        if (GraphicsEnvironment.isHeadless() || EventQueue.isDispatchThread()) {
            update.run();
            return;
        }
        EventQueue.invokeLater(update);
    }
}
