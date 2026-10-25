package com.crosspaynet.payment.settlement;

import com.crosspaynet.payment.contracts.PaymentView;

/**
 * Narrow public port exposed via {@code payment :: settlement} for the future Settlement module
 * to record a confirmed financial settlement outcome on a Payment.
 */
public interface PaymentSettlementPort {

    PaymentView recordSettlement(RecordPaymentSettlementCommand command);
}
