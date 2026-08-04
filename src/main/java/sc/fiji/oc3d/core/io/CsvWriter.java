package sc.fiji.oc3d.core.io;

import ij.measure.ResultsTable;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes RFC 4180 CSV in UTF-8.
 *
 * <p>Every batch output in the family goes through this, so the quoting rules
 * are decided once. They matter more than they look: an image title containing
 * a comma, a Windows path containing a backslash, or a slice label containing a
 * newline will each corrupt a hand-rolled {@code writer.write(a + "," + b)} into
 * a file that opens in Excel with the columns shifted - and shifted columns in a
 * results file are worse than a crash, because nobody notices.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li>A field is quoted if it contains a comma, a double quote, a carriage
 *       return or a newline; otherwise it is written raw.</li>
 *   <li>An embedded double quote is doubled.</li>
 *   <li>{@code null} is written as an empty field, never as {@code "null"}.</li>
 *   <li>Non-finite numbers are written as {@code NaN} rather than as
 *       {@code Infinity}, which no spreadsheet reads back as a number.</li>
 *   <li>Lines end with the platform separator, as {@link BufferedWriter#newLine}
 *       does - readers accept either and the alternative surprises Notepad.</li>
 * </ul>
 */
public final class CsvWriter implements Closeable {

    private final BufferedWriter writer;

    /**
     * Opens {@code file} for writing, truncating it.
     *
     * @throws IOException if the file cannot be created
     * @throws IllegalArgumentException if {@code file} is null
     */
    public CsvWriter(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null (file=null).");
        }
        this.writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
    }

    /** Writes one row. */
    public void row(String... values) throws IOException {
        if (values == null) {
            writer.newLine();
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(quote(values[i]));
        }
        writer.newLine();
    }

    /** Writes one row from a list. */
    public void row(List<String> values) throws IOException {
        row(values == null ? new String[0] : values.toArray(new String[values.size()]));
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * Escapes one field.
     *
     * <p>Public because callers that assemble a line themselves - a header built
     * from a dynamic column set, say - must use the same rules.
     */
    public static String quote(String value) {
        String safe = value == null ? "" : value;
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0;
        if (!quote) return safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    /**
     * Formats a number for a CSV cell.
     *
     * @return {@code "NaN"} for anything non-finite, including both infinities
     */
    public static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "NaN";
    }

    /**
     * Writes a {@link ResultsTable} as CSV: its headings, then its rows.
     *
     * <p>{@code ResultsTable.saveAs} exists but writes ImageJ's own dialect and
     * needs a live ImageJ; this stays headless and matches the batch files
     * beside it.
     *
     * @param table table to write; {@code null} or empty writes an empty file
     */
    public static void write(File file, ResultsTable table) throws IOException {
        CsvWriter out = new CsvWriter(file);
        try {
            if (table == null || table.size() == 0) return;
            String[] headings = table.getHeadings();
            if (headings == null) headings = new String[0];
            out.row(headings);
            for (int row = 0; row < table.size(); row++) {
                List<String> values = new ArrayList<String>(headings.length);
                for (int h = 0; h < headings.length; h++) {
                    values.add(cell(table, headings[h], row));
                }
                out.row(values);
            }
        } finally {
            out.close();
        }
    }

    /**
     * Reads one cell as text, preferring a string column when there is one.
     *
     * <p>A {@code ResultsTable} column can hold either numbers or strings and
     * asking for the wrong kind throws, so both are tried before giving up.
     */
    private static String cell(ResultsTable table, String heading, int row) {
        if (heading == null || heading.trim().isEmpty()) return "";
        try {
            String text = table.getStringValue(heading, row);
            if (text != null) return text;
        } catch (RuntimeException notAStringColumn) {
            // Fall through to the numeric read.
        }
        try {
            return number(table.getValue(heading, row));
        } catch (RuntimeException unreadableCell) {
            return "";
        }
    }
}
