package sc.fiji.oc3d.core.io;

import ij.measure.ResultsTable;

import org.junit.Test;

import sc.fiji.oc3d.core.api.OC3DResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SummaryReporterTest {

    @Test
    public void formatsTheNativeStyleLine() {
        String line = SummaryReporter.format("stack.tif", null, 12, null, 10, 5000, 40);
        assertEquals("stack.tif: 12 objects detected "
                + "(Size filter set to 10-5000 voxels, threshold set to: 40).", line);
    }

    @Test
    public void unboundedMaxSizePrintsAsInfinity() {
        String line = SummaryReporter.format("stack.tif", null, 1, null, 10, Integer.MAX_VALUE, 0);
        assertTrue(line, line.contains("10-Infinity voxels"));
    }

    @Test
    public void aRedirectIsNamedInTheSubject() {
        assertEquals("stack.tif", SummaryReporter.measurementSubject("stack.tif", null));
        assertEquals("stack.tif", SummaryReporter.measurementSubject("stack.tif", ""));
        assertEquals("stack.tif redirect to dapi.tif",
                SummaryReporter.measurementSubject("stack.tif", "dapi.tif"));
        assertEquals("<untitled>", SummaryReporter.measurementSubject(null, null));
    }

    @Test
    public void morphologyMeansAppendWhenAStatisticsTableIsPresent() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Nb of obj. voxels", 0, 10);
        stats.setValue("Volume (mm^3)", 0, 10);
        stats.setValue("Morph_Sphericity", 0, 0.5);
        stats.incrementCounter();
        stats.setValue("Nb of obj. voxels", 1, 20);
        stats.setValue("Volume (mm^3)", 1, 20);
        stats.setValue("Morph_Sphericity", 1, 0.75);

        String line = SummaryReporter.format("stack.tif", null, 2, stats, 1, 100, 0);

        assertTrue(line, line.contains("Morphology means:"));
        assertTrue(line, line.contains("Size=15"));
        assertTrue(line, line.contains("Volume=15"));
        assertTrue(line, line.contains("Sphericity=0.625"));
    }

    @Test
    public void wholeNumbersLoseTheirDecimalPoint() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Nb of obj. voxels", 0, 8);

        String line = SummaryReporter.format("stack.tif", null, 1, stats, 1, 100, 0);

        assertTrue(line, line.contains("Size=8"));
        assertFalse(line, line.contains("Size=8.0"));
    }

    @Test
    public void nonFiniteCellsAreSkippedRatherThanAveragedIn() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Morph_Sphericity", 0, 0.4);
        stats.incrementCounter();
        stats.setValue("Morph_Sphericity", 1, Double.NaN);

        String line = SummaryReporter.format("stack.tif", null, 2, stats, 1, 100, 0);

        assertTrue(line, line.contains("Sphericity=0.4"));
        assertFalse(line, line.contains("NaN"));
    }

    @Test
    public void absentColumnsAreOmittedNotReportedAsNaN() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Nb of obj. voxels", 0, 5);

        String line = SummaryReporter.format("stack.tif", null, 1, stats, 1, 100, 0);

        assertTrue(line, line.contains("Size=5"));
        assertFalse(line, line.contains("Sphericity"));
        assertFalse(line, line.contains("Elongation"));
    }

    @Test
    public void anEmptyTableAddsNoMeansAtAll() {
        String line = SummaryReporter.format("stack.tif", null, 0, new ResultsTable(), 1, 100, 0);
        assertFalse(line, line.contains("Morphology means"));
    }

    @Test
    public void anOC3DResultSuppliesTheCountAndTheTable() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue("Nb of obj. voxels", 0, 42);
        OC3DResult result = new OC3DResult(stats, null, null, null);

        String line = SummaryReporter.format("stack.tif", result, 1, 100, 7);

        assertTrue(line, line.startsWith("stack.tif: 1 objects detected"));
        assertTrue(line, line.contains("threshold set to: 7"));
        assertTrue(line, line.contains("Size=42"));
    }

    @Test
    public void aNullResultReadsAsZeroObjects() {
        String line = SummaryReporter.format("stack.tif", null, (OC3DResult) null, 1, 100, 0);
        assertTrue(line, line.contains(": 0 objects detected"));
        assertFalse(line, line.contains("Morphology means"));
    }
}
