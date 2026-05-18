package com.example.searchengine.infrastructure.admin;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes client IP addresses using SHA-256 (hex-encoded, lowercase, 64 chars).
 *
 * <p>No salt is applied so that the hash is stable across instances and
 * restarts — this allows operators to correlate analytics records from the
 * same client without storing PII (REQ 4.1, design.md §4).</p>
 *
 * <p>Thread-safe: each call creates its own {@link MessageDigest} instance
 * (MessageDigest is not thread-safe).</p>
 */
@Component
public class ClientIpHasher {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Returns the SHA-256 hex-encoded hash (64 lowercase characters) of the
     * given IP string.
     *
     * @param ip the resolved client IP address; must not be null
     * @return 64-character lowercase hex string
     */
    public String hash(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec — this should never happen.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
