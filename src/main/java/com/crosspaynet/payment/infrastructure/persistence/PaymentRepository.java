package com.crosspaynet.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findBySourceAccountIdAndPaymentReference(
            UUID sourceAccountId,
            String paymentReference
    );

    @Modifying
    @Query(value = """
        INSERT INTO payment.payment (
            payment_id,
            version,
            source_account_id,
            payment_reference,
            instruction_fingerprint,
            amount,
            currency,
            beneficiary_name,
            beneficiary_account_number,
            beneficiary_bank_bic,
            status,
            reservation_token,
            route_reference,
            external_dispatch_reference,
            ledger_transaction_id,
            status_reason_code,
            status_reason_detail,
            created_at,
            updated_at
        ) VALUES (
            :paymentId,
            0,
            :sourceAccountId,
            :paymentReference,
            :instructionFingerprint,
            :amount,
            :currency,
            :beneficiaryName,
            :beneficiaryAccountNumber,
            :beneficiaryBankBic,
            :status,
            :reservationToken,
            :routeReference,
            :externalDispatchReference,
            :ledgerTransactionId,
            :statusReasonCode,
            :statusReasonDetail,
            :createdAt,
            :updatedAt
        )
        ON CONFLICT (source_account_id, payment_reference) DO NOTHING
        """, nativeQuery = true)
    int insertClaimOnConflictDoNothing(
            @Param("paymentId") UUID paymentId,
            @Param("sourceAccountId") UUID sourceAccountId,
            @Param("paymentReference") String paymentReference,
            @Param("instructionFingerprint") String instructionFingerprint,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("beneficiaryName") String beneficiaryName,
            @Param("beneficiaryAccountNumber") String beneficiaryAccountNumber,
            @Param("beneficiaryBankBic") String beneficiaryBankBic,
            @Param("status") String status,
            @Param("reservationToken") UUID reservationToken,
            @Param("routeReference") String routeReference,
            @Param("externalDispatchReference") String externalDispatchReference,
            @Param("ledgerTransactionId") UUID ledgerTransactionId,
            @Param("statusReasonCode") String statusReasonCode,
            @Param("statusReasonDetail") String statusReasonDetail,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
