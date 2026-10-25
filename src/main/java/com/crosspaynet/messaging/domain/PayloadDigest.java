package com.crosspaynet.messaging.domain;

import com.crosspaynet.messaging.contracts.MessagingValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic SHA-256 payload integrity digest ({@code crosspaynet-message-digest-v1})
 * over a canonical UTF-8 byte-length-prefixed representation of a {@link PaymentMessage} payload.
 *
 * <p>Note: This is an educational integrity digest for tamper detection in the simulator;
 * it is not a cryptographic digital signature and does not provide asymmetric sender authentication.
 */
public final class PayloadDigest {

    public static final String VERSION_HEADER = "crosspaynet-message-digest-v1";
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    private PayloadDigest(String value) {
        this.value = value;
    }

    public static PayloadDigest ofHex(String hexDigest) {
        if (hexDigest == null || !HEX_64_PATTERN.matcher(hexDigest).matches()) {
            throw new MessagingValidationException(
                    "PayloadDigest must be a 64-character lowercase hexadecimal SHA-256 string."
            );
        }
        return new PayloadDigest(hexDigest);
    }

    public static PayloadDigest compute(String canonicalPayload) {
        String digestInput = toCanonicalDigestInput(canonicalPayload);
        return new PayloadDigest(sha256Hex(digestInput));
    }

    public static String toCanonicalDigestInput(String canonicalPayload) {
        if (canonicalPayload == null || canonicalPayload.isBlank()) {
            throw new MessagingValidationException(
                    "canonicalPayload cannot be null or blank when computing PayloadDigest."
            );
        }
        byte[] utf8Bytes = canonicalPayload.getBytes(StandardCharsets.UTF_8);
        return VERSION_HEADER + "\n" + utf8Bytes.length + ":" + canonicalPayload + "\n";
    }

    public String getValue() {
        return value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available in JDK", ex);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PayloadDigest that = (PayloadDigest) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
