package com.crosspaynet.payment.contracts;

/**
 * Immutable beneficiary details for a cross-border payment instruction.
 */
public record BeneficiaryDetails(
        String beneficiaryName,
        String beneficiaryAccountNumber,
        BicCode beneficiaryBankBic
) {

    public static final int MAX_BENEFICIARY_NAME_LENGTH = 140;
    public static final int MAX_BENEFICIARY_ACCOUNT_LENGTH = 34;

    public BeneficiaryDetails {
        beneficiaryName = validateRequiredText(
                beneficiaryName,
                "beneficiaryName",
                MAX_BENEFICIARY_NAME_LENGTH
        );
        beneficiaryAccountNumber = validateRequiredText(
                beneficiaryAccountNumber,
                "beneficiaryAccountNumber",
                MAX_BENEFICIARY_ACCOUNT_LENGTH
        );
        if (beneficiaryBankBic == null) {
            throw new PaymentValidationException("beneficiaryBankBic cannot be null.");
        }
    }

    private static String validateRequiredText(String raw, String fieldName, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new PaymentValidationException(fieldName + " cannot be null or blank.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            throw new PaymentValidationException(
                    fieldName + " length (" + trimmed.length() + ") exceeds maximum of " + maxLength + " characters."
            );
        }
        if (containsControlCharacters(trimmed)) {
            throw new PaymentValidationException(fieldName + " cannot contain ISO control characters.");
        }
        return trimmed;
    }

    private static boolean containsControlCharacters(String text) {
        return text.codePoints().anyMatch(Character::isISOControl);
    }
}
