package com.example.searchengine.application.ingest;

import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for title and description sanitization.
 *
 * <p><b>Validates: Requirements 19.4</b></p>
 *
 * <p>For any input string {@code s}, the result of {@code Normalizer.sanitize(s)} contains
 * no Unicode Cc codepoints other than 0x09 (tab), 0x0A (newline), and 0x0D (carriage return),
 * and every other codepoint from {@code s} appears in the output in the same order with the
 * same value.</p>
 */
class NormalizerSanitizationPropertyTest {

    private final DefaultNormalizer normalizer = new DefaultNormalizer();

    @Property(tries = 200)
    @Label("Feature: search-engine-service, Property 7: Title and description sanitization")
    void sanitizeRemovesDisallowedControlCharsAndPreservesEverythingElse(@ForAll("arbitraryUnicodeStrings") String input) {
        String result = normalizer.sanitize(input);

        // 1. Assert no Cc codepoint other than 0x09, 0x0A, 0x0D appears in the output
        result.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.CONTROL) {
                assertThat(cp)
                        .as("Control character 0x%04X should not appear in sanitized output", cp)
                        .isIn(0x09, 0x0A, 0x0D);
            }
        });

        // 2. Assert every non-stripped codepoint from the input is preserved in order
        //    Build expected output by filtering the input the same way the spec requires
        String expected = input.codePoints()
                .filter(cp -> cp == 0x09 || cp == 0x0A || cp == 0x0D
                        || Character.getType(cp) != Character.CONTROL)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();

        assertThat(result).isEqualTo(expected);
    }

    @Provide
    Arbitrary<String> arbitraryUnicodeStrings() {
        // Generate arbitrary Unicode strings including control characters
        Arbitrary<Integer> codePoints = Arbitraries.oneOf(
                // Regular ASCII printable characters
                Arbitraries.integers().between(0x20, 0x7E),
                // Allowed control characters (tab, newline, carriage return)
                Arbitraries.of(0x09, 0x0A, 0x0D),
                // Disallowed Cc control characters (C0 range excluding tab/newline/cr)
                Arbitraries.integers().between(0x00, 0x1F)
                        .filter(cp -> cp != 0x09 && cp != 0x0A && cp != 0x0D),
                // Disallowed Cc control characters (C1 range: 0x80-0x9F)
                Arbitraries.integers().between(0x80, 0x9F),
                // BMP non-control characters (Latin Extended, Greek, Cyrillic, etc.)
                Arbitraries.integers().between(0x00A0, 0xD7FF),
                // Supplementary plane characters (emoji, etc.)
                Arbitraries.integers().between(0x10000, 0x10FFFF)
                        .filter(Character::isValidCodePoint)
        );

        return codePoints.list().ofMinSize(0).ofMaxSize(100)
                .map(cps -> {
                    StringBuilder sb = new StringBuilder();
                    for (int cp : cps) {
                        sb.appendCodePoint(cp);
                    }
                    return sb.toString();
                });
    }
}
