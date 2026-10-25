package com.crosspaynet.messaging.infrastructure.persistence;

import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_message", schema = "messaging")
public class PaymentMessageEntity {

    @Id
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "idempotency_key", nullable = false, length = 200, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 35, updatable = false)
    private PaymentMessageType messageType;

    @Column(name = "sender_bic", nullable = false, length = 11, updatable = false)
    private String senderBic;

    @Column(name = "receiver_bic", nullable = false, length = 11, updatable = false)
    private String receiverBic;

    @Column(name = "route_reference", nullable = false, length = 100, updatable = false)
    private String routeReference;

    @Column(name = "amount", nullable = false, columnDefinition = "numeric", updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "beneficiary_name", nullable = false, length = 140, updatable = false)
    private String beneficiaryName;

    @Column(name = "beneficiary_account_number", nullable = false, length = 34, updatable = false)
    private String beneficiaryAccountNumber;

    @Column(name = "payload", nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    @Column(name = "payload_digest", nullable = false, columnDefinition = "bpchar(64)", length = 64, updatable = false)
    private String payloadDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentMessageStatus status;

    @Column(name = "acknowledgment_reference", length = 100)
    private String acknowledgmentReference;

    @Column(name = "peer_rejection_code", length = 35)
    private String peerRejectionCode;

    @Column(name = "peer_rejection_reason", length = 500)
    private String peerRejectionReason;

    @Column(name = "timeout_reason", length = 500)
    private String timeoutReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentMessageEntity() {
    }

    public PaymentMessageEntity(
            UUID messageId,
            UUID paymentId,
            int attemptNumber,
            String idempotencyKey,
            PaymentMessageType messageType,
            String senderBic,
            String receiverBic,
            String routeReference,
            BigDecimal amount,
            String currency,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String payload,
            String payloadDigest,
            PaymentMessageStatus status,
            String acknowledgmentReference,
            String peerRejectionCode,
            String peerRejectionReason,
            String timeoutReason,
            Instant createdAt,
            Instant updatedAt) {
        this.messageId = messageId;
        this.paymentId = paymentId;
        this.attemptNumber = attemptNumber;
        this.idempotencyKey = idempotencyKey;
        this.messageType = messageType;
        this.senderBic = senderBic;
        this.receiverBic = receiverBic;
        this.routeReference = routeReference;
        this.amount = amount;
        this.currency = currency;
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryAccountNumber = beneficiaryAccountNumber;
        this.payload = payload;
        this.payloadDigest = payloadDigest;
        this.status = status;
        this.acknowledgmentReference = acknowledgmentReference;
        this.peerRejectionCode = peerRejectionCode;
        this.peerRejectionReason = peerRejectionReason;
        this.timeoutReason = timeoutReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentMessageType getMessageType() {
        return messageType;
    }

    public String getSenderBic() {
        return senderBic;
    }

    public String getReceiverBic() {
        return receiverBic;
    }

    public String getRouteReference() {
        return routeReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public String getBeneficiaryAccountNumber() {
        return beneficiaryAccountNumber;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadDigest() {
        return payloadDigest;
    }

    public PaymentMessageStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentMessageStatus status) {
        this.status = status;
    }

    public String getAcknowledgmentReference() {
        return acknowledgmentReference;
    }

    public void setAcknowledgmentReference(String acknowledgmentReference) {
        this.acknowledgmentReference = acknowledgmentReference;
    }

    public String getPeerRejectionCode() {
        return peerRejectionCode;
    }

    public void setPeerRejectionCode(String peerRejectionCode) {
        this.peerRejectionCode = peerRejectionCode;
    }

    public String getPeerRejectionReason() {
        return peerRejectionReason;
    }

    public void setPeerRejectionReason(String peerRejectionReason) {
        this.peerRejectionReason = peerRejectionReason;
    }

    public String getTimeoutReason() {
        return timeoutReason;
    }

    public void setTimeoutReason(String timeoutReason) {
        this.timeoutReason = timeoutReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
