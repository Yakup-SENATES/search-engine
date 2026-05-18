package com.example.searchengine.infrastructure.admin;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link ClientIpHasher}.
 *
 * <p><b>Validates: Requirements 4.1</b> — clientIpHash = SHA-256 hex.</p>
 *
 * <p>For any arbitrary IPv4 or IPv6 string, {@code hash(ip)} must:
 * <ul>
 *   <li>Return a 64-character string consisting entirely of lowercase hex characters.</li>
 *   <li>Be deterministic — calling it twice with the same input yields the same output.</li>
 * </ul>
 * </p>
 */
class ClientIpHasherPropertyTest {

    private final ClientIpHasher hasher = new ClientIpHasher();

    @Property(tries = 200)
    @Label("Feature: operability-quick-wins, Property 3: ClientIpHasher determinism — IPv4")
    void hashIsDetermanisticAnd64HexForIpv4(@ForAll("ipv4Addresses") String ip) {
        String first = hasher.hash(ip);
        String second = hasher.hash(ip);

        assertThat(first)
                .as("hash must be 64 characters long (SHA-256 hex)")
                .hasSize(64);
        assertThat(first)
                .as("hash must contain only lowercase hex characters")
                .matches("[0-9a-f]{64}");
        assertThat(first)
                .as("hash must be deterministic — same input yields same output")
                .isEqualTo(second);
    }

    @Property(tries = 200)
    @Label("Feature: operability-quick-wins, Property 3: ClientIpHasher determinism — IPv6")
    void hashIsDeterministicAnd64HexForIpv6(@ForAll("ipv6Addresses") String ip) {
        String first = hasher.hash(ip);
        String second = hasher.hash(ip);

        assertThat(first)
                .as("hash must be 64 characters long (SHA-256 hex)")
                .hasSize(64);
        assertThat(first)
                .as("hash must contain only lowercase hex characters")
                .matches("[0-9a-f]{64}");
        assertThat(first)
                .as("hash must be deterministic — same input yields same output")
                .isEqualTo(second);
    }

    @Provide
    Arbitrary<String> ipv4Addresses() {
        Arbitrary<Integer> octet = Arbitraries.integers().between(0, 255);
        return Combinators.combine(octet, octet, octet, octet)
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);
    }

    @Provide
    Arbitrary<String> ipv6Addresses() {
        Arbitrary<String> group = Arbitraries.integers()
                .between(0, 0xFFFF)
                .map(i -> Integer.toHexString(i));
        return Combinators.combine(group, group, group, group, group, group, group, group)
                .as((g1, g2, g3, g4, g5, g6, g7, g8) ->
                        g1 + ":" + g2 + ":" + g3 + ":" + g4 + ":"
                                + g5 + ":" + g6 + ":" + g7 + ":" + g8);
    }
}
