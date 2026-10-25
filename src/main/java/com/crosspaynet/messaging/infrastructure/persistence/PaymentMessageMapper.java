package com.crosspaynet.messaging.infrastructure.persistence;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.messaging.contracts.MessageId;
import com.crosspaynet.messaging.contracts.MessagingException;
import com.crosspaynet.messaging.domain.PayloadDigest;
import com.crosspaynet.messaging.domain.PaymentMessage;
import com.crosspaynet.messaging.domain.PersistedMessageStateException;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentException;
import com.crosspaynet.payment.contracts.PaymentId;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Maps between the internal {@link PaymentMessage} domain aggregate and {@link PaymentMessageEntity}.
 */
@Component
public class PaymentMessageMapper {

    public PaymentMessageEntity toEntity(PaymentMessage domain) {
        if (domain == null) {
            throw new IllegalArgumentException("PaymentMessage domain object cannot be null.");
        }

        return new PaymentMessageEntity(
                domain.getId().getValue(),
                domain.getPaymentId().getValue(),
                domain.getAttemptNumber(),
                domain.getIdempotencyKey(),
                domain.getMessageType(),
                domain.getSenderBic().getValue(),
                domain.getReceiverBic().getValue(),
                domain.getRouteReference(),
                domain.getAmount().getAmount(),
                domain.getAmount().getCurrency().getCode(),
                domain.getBeneficiaryName(),
                domain.getBeneficiaryAccountNumber(),
                domain.getPayload(),
                domain.getPayloadDigest().getValue(),
                domain.getStatus(),
                domain.getAcknowledgmentReference().orElse(null),
                domain.getPeerRejectionCode().orElse(null),
                domain.getPeerRejectionReason().orElse(null),
                domain.getTimeoutReason().orElse(null),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public void updateEntity(PaymentMessage domain, PaymentMessageEntity entity) {
        if (domain == null) {
            throw new IllegalArgumentException("PaymentMessage domain object cannot be null.");
        }
        if (entity == null) {
            throw new IllegalArgumentException("PaymentMessageEntity cannot be null.");
        }

        if (!Objects.equals(domain.getId().getValue(), entity.getMessageId())
                || !Objects.equals(domain.getPaymentId().getValue(), entity.getPaymentId())
                || domain.getAttemptNumber() != entity.getAttemptNumber()
                || !Objects.equals(domain.getIdempotencyKey(), entity.getIdempotencyKey())
                || domain.getMessageType() != entity.getMessageType()
                || !Objects.equals(domain.getSenderBic().getValue(), entity.getSenderBic())
                || !Objects.equals(domain.getReceiverBic().getValue(), entity.getReceiverBic())
                || !Objects.equals(domain.getRouteReference(), entity.getRouteReference())
                || entity.getAmount() == null
                || entity.getAmount().compareTo(domain.getAmount().getAmount()) != 0
                || !Objects.equals(domain.getAmount().getCurrency().getCode(), entity.getCurrency())
                || !Objects.equals(domain.getBeneficiaryName(), entity.getBeneficiaryName())
                || !Objects.equals(domain.getBeneficiaryAccountNumber(), entity.getBeneficiaryAccountNumber())
                || !Objects.equals(domain.getPayload(), entity.getPayload())
                || !Objects.equals(domain.getPayloadDigest().getValue(), entity.getPayloadDigest())
                || !Objects.equals(domain.getCreatedAt(), entity.getCreatedAt())) {
            throw new PersistedMessageStateException(
                    "Immutable PaymentMessage attributes on PaymentMessageEntity '" + entity.getMessageId()
                            + "' do not match reconstructed domain PaymentMessage '" + domain.getId() + "'."
            );
        }

        entity.setStatus(domain.getStatus());
        entity.setAcknowledgmentReference(domain.getAcknowledgmentReference().orElse(null));
        entity.setPeerRejectionCode(domain.getPeerRejectionCode().orElse(null));
        entity.setPeerRejectionReason(domain.getPeerRejectionReason().orElse(null));
        entity.setTimeoutReason(domain.getTimeoutReason().orElse(null));
        entity.setUpdatedAt(domain.getUpdatedAt());
    }

    public PaymentMessage toDomain(PaymentMessageEntity entity) {
        if (entity == null) {
            return null;
        }

        try {
            requireUntrimmedPersistedText(entity.getSenderBic(), "senderBic");
            requireUntrimmedPersistedText(entity.getReceiverBic(), "receiverBic");
            requireUntrimmedPersistedText(entity.getRouteReference(), "routeReference");
            requireUntrimmedPersistedText(entity.getBeneficiaryName(), "beneficiaryName");
            requireUntrimmedPersistedText(entity.getBeneficiaryAccountNumber(), "beneficiaryAccountNumber");

            MessageId messageId = new MessageId(entity.getMessageId());
            PaymentId paymentId = new PaymentId(entity.getPaymentId());
            BicCode senderBic = new BicCode(entity.getSenderBic());
            BicCode receiverBic = new BicCode(entity.getReceiverBic());
            Currency currency = new Currency(entity.getCurrency());
            Money amount = new Money(entity.getAmount(), currency);
            PayloadDigest digest = PayloadDigest.ofHex(entity.getPayloadDigest());

            return PaymentMessage.restore(
                    messageId,
                    paymentId,
                    entity.getAttemptNumber(),
                    entity.getIdempotencyKey(),
                    entity.getMessageType(),
                    senderBic,
                    receiverBic,
                    entity.getRouteReference(),
                    amount,
                    entity.getBeneficiaryName(),
                    entity.getBeneficiaryAccountNumber(),
                    entity.getPayload(),
                    digest,
                    entity.getStatus(),
                    entity.getAcknowledgmentReference(),
                    entity.getPeerRejectionCode(),
                    entity.getPeerRejectionReason(),
                    entity.getTimeoutReason(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        } catch (PersistedMessageStateException ex) {
            throw ex;
        } catch (MessagingException | PaymentException | IllegalArgumentException | NullPointerException ex) {
            throw new PersistedMessageStateException(
                    "Corrupted persisted PaymentMessage state for messageId '" + entity.getMessageId()
                            + "': " + ex.getMessage(),
                    ex
            );
        }
    }

    private static void requireUntrimmedPersistedText(String value, String fieldName) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new PersistedMessageStateException(
                    "Persisted " + fieldName + " cannot be null, blank, or contain leading/trailing whitespace."
            );
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new PersistedMessageStateException(
                    "Persisted " + fieldName + " cannot contain ISO control characters."
            );
        }
    }
}
