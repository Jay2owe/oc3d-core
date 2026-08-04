package sc.fiji.oc3d.core.io;

import ij.measure.ResultsTable;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CsvWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void quotesOnlyWhenNecessary() {
        assertEquals("plain", CsvWriter.quote("plain"));
        assertEquals("\"has,comma\"", CsvWriter.quote("has,comma"));
        assertEquals("\"has\"\"quote\"", CsvWriter.quote("has\"quote"));
        assertEquals("\"has\nnewline\"", CsvWriter.quote("has\nnewline"));
        assertEquals("\"has\rreturn\"", CsvWriter.quote("has\rreturn"));
        assertEquals("has\\backslash", CsvWriter.quote("has\\backslash"));
    }

    @Test
    public void nullIsAnEmptyFieldNotTheWordNull() {
        assertEquals("", CsvWriter.quote(null));
    }

    @Test
    public void nonFiniteNumbersBecomeNaN() {
        assertEquals("1.5", CsvWriter.number(1.5));
        assertEquals("NaN", CsvWriter.number(Double.NaN));
        assertEquals("NaN", CsvWriter.number(Double.POSITIVE_INFINITY));
        assertEquals("NaN", CsvWriter.number(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void writesRowsAsUtf8() throws IOException {
        File file = folder.newFile("rows.csv");
        CsvWriter writer = new CsvWriter(file);
        try {
            writer.row("a", "b,c", null);
            writer.row(Arrays.asList("1", "2", "3"));
        } finally {
            writer.close();
        }

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("a,\"b,c\",", lines.get(0));
        assertEquals("1,2,3", lines.get(1));
    }

    @Test
    public void aTitleWithACommaStaysInOneColumn() throws IOException {
        File file = folder.newFile("title.csv");
        CsvWriter writer = new CsvWriter(file);
        try {
            writer.row("SourceRelativePath", "ObjectCount");
            writer.row("Exp1, plate 2/stack.tif", "17");
        } finally {
            writer.close();
        }

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals("\"Exp1, plate 2/stack.tif\",17", lines.get(1));
    }

    @Test
    public void writesAResultsTableWithItsHeadings() throws IOException {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.setValue("Label", 0, 1);
        table.setValue("Volume", 0, 12.5);
        table.incrementCounter();
        table.setValue("Label", 1, 2);
        table.setValue("Volume", 1, Double.NaN);

        File file = folder.newFile("table.csv");
        CsvWriter.write(file, table);

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("Label"));
        assertTrue(lines.get(0), lines.get(0).contains("Volume"));
        assertTrue(lines.get(1), lines.get(1).contains("12.5"));
        assertTrue(lines.get(2), lines.get(2).contains("NaN"));
    }

    @Test
    public void anEmptyTableWritesAnEmptyFile() throws IOException {
        File file = folder.newFile("empty.csv");
        CsvWriter.write(file, new ResultsTable());
        assertEquals(0, Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).size());

        File nullTable = folder.newFile("null.csv");
        CsvWriter.write(nullTable, null);
        assertEquals(0, Files.readAllLines(nullTable.toPath(), StandardCharsets.UTF_8).size());
    }

    @Test
    public void nullFileIsRejected() {
        try {
            new CsvWriter(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("file"));
        } catch (IOException unexpected) {
            fail("expected IllegalArgumentException, got " + unexpected);
        }
    }
}
