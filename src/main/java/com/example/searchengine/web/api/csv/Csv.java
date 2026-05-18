package com.example.searchengine.web.api.csv;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Pure-Java CSV utility implementing RFC 4180 escaping rules.
 *
 * <ul>
 *   <li>Fields containing comma, double quote, CR, or LF are wrapped in double quotes.</li>
 *   <li>Inner double quotes are doubled ({@code "} → {@code ""}).</li>
 *   <li>Rows are terminated with CRLF.</li>
 * </ul>
 *
 * <p>No framework dependencies — this class is usable anywhere in the JVM.
 */
public final class Csv {

    private static final String CRLF = "\r\n";

    private Csv() {
        // utility class
    }

    /**
     * Escapes a single CSV field per RFC 4180.
     *
     * <p>Returns the value unchanged if it contains no special characters.
     * Wraps the value in double quotes if it contains a comma, double quote, CR, or LF.
     * Doubles any inner double quotes.
     *
     * @param value the field value (may be {@code null}, treated as empty string)
     * @return the escaped field
     */
    public static String escapeField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 4);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Formats a row of fields into a single CSV line (terminated with CRLF).
     *
     * <p>Each field is escaped via {@link #escapeField(String)} and joined with commas.
     *
     * @param fields the fields to format
     * @return the formatted CSV row ending with CRLF
     */
    public static String formatRow(String... fields) {
        StringJoiner joiner = new StringJoiner(",");
        for (String field : fields) {
            joiner.add(escapeField(field));
        }
        return joiner.toString() + CRLF;
    }

    /**
     * Formats multiple rows into a CSV document.
     *
     * @param rows the rows to format; each element is an array of field values
     * @return the complete CSV text (each row terminated with CRLF)
     */
    public static String formatRows(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(formatRow(row));
        }
        return sb.toString();
    }

    /**
     * Parses a single CSV line back into fields, handling quoted fields with
     * embedded commas, quotes, and newlines (RFC 4180).
     *
     * <p>This method handles a single logical CSV record which may span multiple
     * physical lines if fields contain embedded newlines. The input should include
     * any embedded newlines within quoted fields.
     *
     * @param line the CSV line to parse (trailing CRLF or LF is stripped)
     * @return the list of parsed field values
     */
    public static List<String> parseLine(String line) {
        if (line == null) {
            return List.of();
        }
        // Strip trailing CRLF or LF
        if (line.endsWith("\r\n")) {
            line = line.substring(0, line.length() - 2);
        } else if (line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
        } else if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }

        List<String> fields = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i <= len) {
            if (i == len) {
                // Trailing comma produced an empty field
                fields.add("");
                break;
            }

            if (line.charAt(i) == '"') {
                // Quoted field
                StringBuilder sb = new StringBuilder();
                i++; // skip opening quote
                while (i < len) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        // Check for escaped quote (doubled)
                        if (i + 1 < len && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                        } else {
                            // Closing quote
                            i++; // skip closing quote
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                fields.add(sb.toString());
                // Skip the comma after the closing quote (or we're at end)
                if (i < len && line.charAt(i) == ',') {
                    i++;
                } else {
                    // End of input
                    break;
                }
            } else {
                // Unquoted field — read until comma or end
                int start = i;
                while (i < len && line.charAt(i) != ',') {
                    i++;
                }
                fields.add(line.substring(start, i));
                if (i < len) {
                    i++; // skip comma
                } else {
                    break;
                }
            }
        }

        return fields;
    }
}
