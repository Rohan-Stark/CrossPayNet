package com.crosspaynet.payment.infrastructure.persistence;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.domain.InstructionFingerprint;
import com.crosspaynet.payment.domain.Payment;
import com.crosspaynet.payment.domain.PersistedPaymentStateException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Maps between the internal {@link Payment} domain aggregate and {@link PaymentEntity}.
 */
@Component
public class PaymentMapper {

    public PaymentEntity toEntity(Payment domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Payment domain object cannot be null.");
        }

        PaymentInstruction instruction = domain.getInstruction();
        BeneficiaryDetails beneficiary = instruction.beneficiary();

        return new PaymentEntity(
                domain.getId().getValue(),
                instruction.sourceAccountId().getValue(),
                instruction.paymentReference().getValue(),
                domain.getInstructionFingerprint().getValue(),
                instruction.amount().getAmount(),
                instruction.amount().getCurrency().getCode(),
                beneficiary.beneficiaryName(),
                beneficiary.beneficiaryAccountNumber(),
                beneficiary.beneficiaryBankBic().getValue(),
                domain.getStatus(),
                domain.getReservationToken().map(ReservationToken::getValue).orElse(null),
                domain.getRouteReference().orElse(null),
                domain.getExternalDispatchReference().orElse(null),
                domain.getLedgerTransactionId().orElse(null),
                domain.getStatusReasonCode().orElse(null),
                domain.getStatusReasonDetail().orElse(null),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public void updateEntity(Payment domain, PaymentEntity entity) {
        if (domain == null) {
            throw new IllegalArgumentException("Payment domain object cannot be null.");
        }
        if (entity == null) {
            throw new IllegalArgumentException("PaymentEntity cannot be null.");
        }

        PaymentInstruction instruction = domain.getInstruction();
        BeneficiaryDetails beneficiary = instruction.beneficiary();

        if (!Objects.equals(domain.getId().getValue(), entity.getPaymentId())
                || !Objects.equals(instruction.sourceAccountId().getValue(), entity.getSourceAccountId())
                || !Objects.equals(instruction.paymentReference().getValue(), entity.getPaymentReference())
                || !Objects.equals(domain.getInstructionFingerprint().getValue(), entity.getInstructionFingerprint())
                || !Objects.equals(instruction.amount().getCurrency().getCode(), entity.getCurrency())
                || entity.getAmount() == null
                || entity.getAmount().compareTo(instruction.amount().getAmount()) != 0
                || !Objects.equals(beneficiary.beneficiaryName(), entity.getBeneficiaryName())
                || !Objects.equals(beneficiary.beneficiaryAccountNumber(), entity.getBeneficiaryAccountNumber())
                || !Objects.equals(beneficiary.beneficiaryBankBic().getValue(), entity.getBeneficiaryBankBic())
                || !Objects.equals(domain.getCreatedAt(), entity.getCreatedAt())) {
            throw new PersistedPaymentStateException(
                    "Immutable Payment attributes on PaymentEntity '" + entity.getPaymentId()
                            + "' do not match reconstructed domain Payment '" + domain.getId() + "'."
            );
        }

        entity.setStatus(domain.getStatus());
        entity.setReservationToken(domain.getReservationToken().map(ReservationToken::getValue).orElse(null));
        entity.setRouteReference(domain.getRouteReference().orElse(null));
        entity.setExternalDispatchReference(domain.getExternalDispatchReference().orElse(null));
        entity.setLedgerTransactionId(domain.getLedgerTransactionId().orElse(null));
        entity.setStatusReasonCode(domain.getStatusReasonCode().orElse(null));
        entity.setStatusReasonDetail(domain.getStatusReasonDetail().orElse(null));
        entity.setUpdatedAt(domain.getUpdatedAt());
    }

    public Payment toDomain(PaymentEntity entity) {
        if (entity == null) {
            return null;
        }

        try {
            requireUntrimmedPersistedText(entity.getPaymentReference(), "paymentReference");
            requireUntrimmedPersistedText(entity.getBeneficiaryName(), "beneficiaryName");
            requireUntrimmedPersistedText(entity.getBeneficiaryAccountNumber(), "beneficiaryAccountNumber");
            requireUntrimmedPersistedText(entity.getBeneficiaryBankBic(), "beneficiaryBankBic");

            PaymentId paymentId = new PaymentId(entity.getPaymentId());
            AccountId sourceAccountId = new AccountId(entity.getSourceAccountId());
            PaymentReference paymentReference = new PaymentReference(entity.getPaymentReference());
            Currency currency = new Currency(entity.getCurrency());
            Money amount = new Money(entity.getAmount(), currency);
            BicCode bicCode = new BicCode(entity.getBeneficiaryBankBic());
            BeneficiaryDetails beneficiary = new BeneficiaryDetails(
                    entity.getBeneficiaryName(),
                    entity.getBeneficiaryAccountNumber(),
                    bicCode
            );
            PaymentInstruction instruction = new PaymentInstruction(
                    sourceAccountId,
                    paymentReference,
                    beneficiary,
                    amount
            );
            InstructionFingerprint fingerprint = InstructionFingerprint.ofHex(entity.getInstructionFingerprint());
            ReservationToken reservationToken = entity.getReservationToken() != null
                    ? new ReservationToken(entity.getReservationToken())
                    : null;

            return Payment.restore(
                    paymentId,
                    instruction,
                    fingerprint,
                    entity.getStatus(),
                    reservationToken,
                    entity.getRouteReference(),
                    entity.getExternalDispatchReference(),
                    entity.getLedgerTransactionId(),
                    entity.getStatusReasonCode(),
                    entity.getStatusReasonDetail(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        } catch (PersistedPaymentStateException ex) {
            throw ex;
        } catch (PaymentException | IllegalArgumentException | NullPointerException ex) {
            throw new PersistedPaymentStateException(
                    "Corrupted persisted Payment state for paymentId '" + entity.getPaymentId() + "': " + ex.getMessage(),
                    ex
            );
        }
    }

    private static void requireUntrimmedPersistedText(String value, String fieldName) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new PersistedPaymentStateException(
                    "Persisted " + fieldName + " cannot be null, blank, or contain leading/trailing whitespace."
            );
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new PersistedPaymentStateException(
                    "Persisted " + fieldName + " cannot contain ISO control characters."
            );
        }
    }
}
