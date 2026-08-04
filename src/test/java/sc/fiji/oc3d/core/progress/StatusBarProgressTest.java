package sc.fiji.oc3d.core.progress;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StatusBarProgressTest {

    @Test
    public void stepsAreNumberedAndDescribed() {
        assertEquals("Step 2/5: Measuring", StatusBarProgress.formatStep(2, 5, "Measuring"));
        assertEquals("Step 1/1: Working", StatusBarProgress.formatStep(1, 1, null));
        assertEquals("Step 1/1: Working", StatusBarProgress.formatStep(1, 1, "   "));
        assertEquals("Step 3/3: Done", StatusBarProgress.formatStep(9, 3, " Done "));
        assertEquals("a zero total would divide by nothing",
                "Step 1/1: Go", StatusBarProgress.formatStep(0, 0, "Go"));
    }

    @Test
    public void barFractionsSpanZeroToOne() {
        assertEquals(0.0, StatusBarProgress.progressAtStepStart(1, 4), 0.0);
        assertEquals(0.25, StatusBarProgress.progressAtStepEnd(1, 4), 0.0);
        assertEquals(0.75, StatusBarProgress.progressAtStepStart(4, 4), 0.0);
        assertEquals(1.0, StatusBarProgress.progressAtStepEnd(4, 4), 0.0);
    }

    @Test
    public void stepIndicesAreClampedIntoRange() {
        assertEquals(0.0, StatusBarProgress.progressAtStepStart(-3, 4), 0.0);
        assertEquals(1.0, StatusBarProgress.progressAtStepEnd(99, 4), 0.0);
    }

    @Test
    public void reportingIsSafeWithoutALiveImageJ() {
        // Every IJ call is guarded; none of these may throw in a headless test.
        StatusBarProgress progress = StatusBarProgress.steps(3);
        progress.step("Detecting");
        progress.detail("slice 4");
        progress.progress("Finding structures", 4, 10);
        progress.progress("Finding structures", 4, 0);
        progress.finishStep();
        progress.finish("Done");
        progress.error("3D Objects Counter+", "something failed");
        progress.error(null, null);
    }

    @Test
    public void theSilentReporterAcceptsEveryCall() {
        StatusBarProgress none = StatusBarProgress.none();
        none.step("ignored");
        none.detail("ignored");
        none.progress("ignored", 1, 2);
        none.finishStep();
        none.finish("ignored");
        none.error("plugin", "ignored");
    }

    @Test
    public void itSatisfiesTheProgressListenerContract() {
        ProgressListener listener = StatusBarProgress.steps(2);
        listener.progress("Labelling", 1, 2);
        ProgressListener.NONE.progress("Labelling", 1, 2);
    }
}
