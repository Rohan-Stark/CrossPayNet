package com.crosspaynet.messaging.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentMessageRepository extends JpaRepository<PaymentMessageEntity, UUID> {

    Optional<PaymentMessageEntity> findByPaymentIdAndAttemptNumber(UUID paymentId, int attemptNumber);

    Optional<PaymentMessageEntity> findByIdempotencyKey(String idempotencyKey);

    List<PaymentMessageEntity> findByPaymentIdOrderByAttemptNumberAsc(UUID paymentId);

    @Modifying
    @Query(value = """
        INSERT INTO messaging.payment_message (
            message_id,
            version,
            payment_id,
            attempt_number,
            idempotency_key,
            message_type,
            sender_bic,
            receiver_bic,
            route_reference,
            amount,
            currency,
            beneficiary_name,
            beneficiary_account_number,
            payload,
            payload_digest,
            status,
            acknowledgment_reference,
            peer_rejection_code,
            peer_rejection_reason,
            timeout_reason,
            created_at,
            updated_at
        ) VALUES (
            :messageId,
            0,
            :paymentId,
            :attemptNumber,
            :idempotencyKey,
            :messageType,
            :senderBic,
            :receiverBic,
            :routeReference,
            :amount,
            :currency,
            :beneficiaryName,
            :beneficiaryAccountNumber,
            :payload,
            :payloadDigest,
            :status,
            :acknowledgmentReference,
            :peerRejectionCode,
            :peerRejectionReason,
            :timeoutReason,
            :createdAt,
            :updatedAt
        )
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertClaimOnConflictDoNothing(
            @Param("messageId") UUID messageId,
            @Param("paymentId") UUID paymentId,
            @Param("attemptNumber") int attemptNumber,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("messageType") String messageType,
            @Param("senderBic") String senderBic,
            @Param("receiverBic") String receiverBic,
            @Param("routeReference") String routeReference,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("beneficiaryName") String beneficiaryName,
            @Param("beneficiaryAccountNumber") String beneficiaryAccountNumber,
            @Param("payload") String payload,
            @Param("payloadDigest") String payloadDigest,
            @Param("status") String status,
            @Param("acknowledgmentReference") String acknowledgmentReference,
            @Param("peerRejectionCode") String peerRejectionCode,
            @Param("peerRejectionReason") String peerRejectionReason,
            @Param("timeoutReason") String timeoutReason,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
