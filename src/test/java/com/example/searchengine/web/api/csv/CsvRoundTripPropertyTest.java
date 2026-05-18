package com.example.searchengine.web.api.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;


/**
 * Property-based test verifying the CSV round-trip invariant:
 * formatting fields into a CSV row and parsing them back yields the original input.
 *
 * <p><b>Validates: Requirements 5.4</b>
 *
 * <p>Property: {@code Feature: operability-quick-wins, Property 2: CSV round-trip preserves payload}
 *
 * <p>For any list of string fields (including printable Unicode, comma, quote, CR, LF),
 * {@code Csv.parseLine(Csv.formatRow(fields))} MUST return a list deeply equal to the
 * original fields (with nulls normalized to empty strings, matching {@code escapeField} behavior).
 */
class CsvRoundTripPropertyTest {

    @Property(tries = 200)
    @Label("Feature: operability-quick-wins, Property 2: CSV round-trip preserves payload")
    void parseAfterFormatRoundTrip(@ForAll("csvFields") List<String> fields) {
        // Format the fields into a CSV row
        String[] fieldArray = fields.toArray(String[]::new);
        String csvRow = Csv.formatRow(fieldArray);

        // Parse the CSV row back into fields
        List<String> parsed = Csv.parseLine(csvRow);

        // Assert round-trip equality
        assertThat(parsed)
                .as("parse(format(fields)) must equal original fields; row was: %s", csvRow)
                .isEqualTo(fields);
    }

    // ─── Generators ───────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<String>> csvFields() {
        return csvFieldValue().list().ofMinSize(1).ofMaxSize(10);
    }

    /**
     * Generates arbitrary CSV field values containing printable Unicode characters
     * plus the special CSV characters: comma, double quote, CR, and LF.
     *
     * <p>This exercises the full RFC 4180 escaping logic including edge cases
     * with embedded newlines and quotes.
     */
    private Arbitrary<String> csvFieldValue() {
        // Character set: printable ASCII + special CSV chars + some Unicode
        Arbitrary<Character> chars = Arbitraries.frequencyOf(
                // Regular printable ASCII (space through tilde)
                net.jqwik.api.Tuple.of(70, Arbitraries.chars().range(' ', '~')),
                // Comma — triggers quoting
                net.jqwik.api.Tuple.of(8, Arbitraries.just(',')),
                // Double quote — triggers quoting + doubling
                net.jqwik.api.Tuple.of(8, Arbitraries.just('"')),
                // Carriage return — triggers quoting
                net.jqwik.api.Tuple.of(5, Arbitraries.just('\r')),
                // Line feed — triggers quoting
                net.jqwik.api.Tuple.of(5, Arbitraries.just('\n')),
                // Some printable Unicode beyond ASCII
                net.jqwik.api.Tuple.of(4, Arbitraries.chars().range('\u00C0', '\u024F'))
        );

        return chars.list().ofMinSize(0).ofMaxSize(50)
                .map(charList -> {
                    StringBuilder sb = new StringBuilder(charList.size());
                    for (Character c : charList) {
                        sb.append(c);
                    }
                    return sb.toString();
                });
    }
}
