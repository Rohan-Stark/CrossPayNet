package com.crosspaynet.payment.domain;

import java.math.BigDecimal;

/**
 * Single source of truth for Payment domain bounds and structural limits.
 */
public final class PaymentLimits {

    public static final int MAX_SCALE = 4;
    public static final int MAX_NUMERIC_PRECISION = 19;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");
    public static final int MAX_PAYMENT_REFERENCE_LENGTH = 255;
    public static final int MAX_BENEFICIARY_NAME_LENGTH = 140;
    public static final int MAX_BENEFICIARY_ACCOUNT_LENGTH = 34;
    public static final int MIN_BIC_LENGTH = 8;
    public static final int MAX_BIC_LENGTH = 11;
    public static final int MAX_ROUTE_REFERENCE_LENGTH = 100;
    public static final int MAX_EXTERNAL_DISPATCH_REFERENCE_LENGTH = 100;
    public static final int MAX_REASON_DETAIL_LENGTH = 500;

    private PaymentLimits() {
        // Prevent instantiation
    }
}
