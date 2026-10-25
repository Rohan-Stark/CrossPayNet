package com.crosspaynet.messaging.domain;

import com.crosspaynet.common.money.Money;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.MessagingValidationException;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic builder for canonical ISO 20022-inspired {@code pacs.008.001.08}
 * ({@code FIToFICustomerCreditTransfer}) wire payloads.
 *
 * <p>Uses UTF-8 byte-length-prefixed token encoding so identical message inputs always
 * produce identical payload bytes and {@link PayloadDigest} values, and no special characters
 * in beneficiary names can introduce structural ambiguity.
 */
public final class Pacs008PayloadBuilder {

    public static final String SCHEMA_IDENTIFIER = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";

    private Pacs008PayloadBuilder() {
    }

    public static String buildCanonicalPayload(
            MessageId messageId,
            PaymentId paymentId,
            int attemptNumber,
            PaymentMessageType messageType,
            BicCode senderBic,
            BicCode receiverBic,
            String routeReference,
            Money amount,
            String beneficiaryName,
            String beneficiaryAccountNumber) {
        if (messageId == null) {
            throw new MessagingValidationException("messageId cannot be null.");
        }
        if (paymentId == null) {
            throw new MessagingValidationException("paymentId cannot be null.");
        }
        if (attemptNumber < MessagingLimits.MIN_ATTEMPT_NUMBER
                || attemptNumber > MessagingLimits.MAX_ATTEMPT_NUMBER) {
            throw new MessagingValidationException(
                    "attemptNumber must be between " + MessagingLimits.MIN_ATTEMPT_NUMBER
                            + " and " + MessagingLimits.MAX_ATTEMPT_NUMBER + "."
            );
        }
        if (messageType != PaymentMessageType.PACS_008) {
            throw new MessagingValidationException("Unsupported PaymentMessageType: " + messageType);
        }
        if (senderBic == null) {
            throw new MessagingValidationException("senderBic cannot be null.");
        }
        if (receiverBic == null) {
            throw new MessagingValidationException("receiverBic cannot be null.");
        }
        if (routeReference == null || routeReference.isBlank() || !routeReference.equals(routeReference.trim())) {
            throw new MessagingValidationException("routeReference must be non-blank and trimmed.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new MessagingValidationException("amount must be non-null and strictly positive.");
        }
        if (!MessagingLimits.isSupportedCurrencyCode(amount.getCurrency().getCode())) {
            throw new MessagingValidationException(
                    "Unsupported payment message currency: " + amount.getCurrency().getCode()
            );
        }
        if (beneficiaryName == null || beneficiaryName.isBlank() || !beneficiaryName.equals(beneficiaryName.trim())) {
            throw new MessagingValidationException("beneficiaryName must be non-blank and trimmed.");
        }
        if (beneficiaryAccountNumber == null
                || beneficiaryAccountNumber.isBlank()
                || !beneficiaryAccountNumber.equals(beneficiaryAccountNumber.trim())) {
            throw new MessagingValidationException("beneficiaryAccountNumber must be non-blank and trimmed.");
        }

        String formattedAmount = amount.getAmount()
                .setScale(MessagingLimits.MAX_SCALE, RoundingMode.UNNECESSARY)
                .toPlainString();

        StringBuilder sb = new StringBuilder(384);
        sb.append(SCHEMA_IDENTIFIER).append('\n');
        sb.append("GrpHdr.MsgId=").append(encodeToken(messageId.getValue().toString())).append('\n');
        sb.append("GrpHdr.InstgAgt.BICFI=").append(encodeToken(senderBic.getValue())).append('\n');
        sb.append("GrpHdr.InstdAgt.BICFI=").append(encodeToken(receiverBic.getValue())).append('\n');
        sb.append("CdtTrfTxInf.PmtId.InstrId=").append(encodeToken(paymentId.getValue().toString())).append('\n');
        sb.append("CdtTrfTxInf.PmtId.EndToEndId=")
                .append(encodeToken(paymentId.getValue() + ":" + attemptNumber))
                .append('\n');
        sb.append("CdtTrfTxInf.IntrBkSttlmAmt.Ccy=").append(encodeToken(amount.getCurrency().getCode())).append('\n');
        sb.append("CdtTrfTxInf.IntrBkSttlmAmt.Val=").append(encodeToken(formattedAmount)).append('\n');
        sb.append("CdtTrfTxInf.SttlmInf.RouteRef=").append(encodeToken(routeReference)).append('\n');
        sb.append("CdtTrfTxInf.Cdtr.Nm=").append(encodeToken(beneficiaryName)).append('\n');
        sb.append("CdtTrfTxInf.CdtrAcct.Id=").append(encodeToken(beneficiaryAccountNumber)).append('\n');
        sb.append("CdtTrfTxInf.CdtrAgt.BICFI=").append(encodeToken(receiverBic.getValue()));
        return sb.toString();
    }

    static String encodeToken(String value) {
        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        return utf8Bytes.length + ":" + value;
    }
}
