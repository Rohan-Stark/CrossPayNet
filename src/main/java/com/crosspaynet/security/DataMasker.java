package com.crosspaynet.security;

/**
 * Utility for masking sensitive customer and financial data before logging.
 */
public final class DataMasker {

    private DataMasker() {}

    /**
     * Masks an account number or reference, revealing only the last 4 characters.
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "****";
        }
        String trimmed = accountNumber.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * Masks a personal name or entity name.
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "***";
        }
        String trimmed = name.trim();
        if (trimmed.length() <= 2) {
            return trimmed.charAt(0) + "***";
        }
        return trimmed.charAt(0) + "***" + trimmed.charAt(trimmed.length() - 1);
    }
}
