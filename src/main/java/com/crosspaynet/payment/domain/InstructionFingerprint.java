package com.crosspaynet.payment.domain;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentValidationException;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic SHA-256 fingerprint over a versioned, UTF-8 byte-length-prefixed canonical
 * representation of a {@link PaymentInstruction}.
 *
 * <p>Used to distinguish idempotent duplicate submissions from conflicting reuse of a
 * {@code (sourceAccountId, paymentReference)} pair.
 */
public final class InstructionFingerprint {

    public static final String VERSION_HEADER = "crosspaynet-payment-fingerprint-v1";
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    private InstructionFingerprint(String value) {
        this.value = value;
    }

    public static InstructionFingerprint ofHex(String hexDigest) {
        if (hexDigest == null || !HEX_64_PATTERN.matcher(hexDigest).matches()) {
            throw new PaymentValidationException(
                    "InstructionFingerprint must be a 64-character lowercase hexadecimal SHA-256 string."
            );
        }
        return new InstructionFingerprint(hexDigest);
    }

    public static InstructionFingerprint compute(PaymentInstruction instruction) {
        String canonical = toCanonicalString(instruction);
        return new InstructionFingerprint(sha256Hex(canonical));
    }

    public static String toCanonicalString(PaymentInstruction instruction) {
        if (instruction == null) {
            throw new PaymentValidationException("instruction cannot be null when computing InstructionFingerprint.");
        }
        PaymentSupportedCurrencyPolicy.requireSupported(instruction.amount().getCurrency());

        StringBuilder sb = new StringBuilder(256);
        sb.append(VERSION_HEADER).append('\n');
        sb.append(encodeToken(instruction.sourceAccountId().getValue().toString())).append('\n');
        sb.append(encodeToken(instruction.paymentReference().getValue())).append('\n');
        sb.append(encodeToken(instruction.amount().getCurrency().getCode())).append('\n');
        sb.append(encodeToken(formatCanonicalAmount(instruction.amount()))).append('\n');
        sb.append(encodeToken(instruction.beneficiary().beneficiaryName())).append('\n');
        sb.append(encodeToken(instruction.beneficiary().beneficiaryAccountNumber())).append('\n');
        sb.append(encodeToken(instruction.beneficiary().beneficiaryBankBic().getValue())).append('\n');
        return sb.toString();
    }

    public String getValue() {
        return value;
    }

    private static String formatCanonicalAmount(Money money) {
        return money.getAmount()
                .setScale(PaymentLimits.MAX_SCALE, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    static String encodeToken(String value) {
        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        return utf8Bytes.length + ":" + value;
    }

    private static String sha256Hex(String canonicalInput) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available in JDK", ex);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InstructionFingerprint that = (InstructionFingerprint) o;
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
