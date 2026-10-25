package com.crosspaynet.payment.infrastructure.persistence;

import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
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
@Table(name = "payment", schema = "payment")
public class PaymentEntity {

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "payment_reference", nullable = false, length = 255, updatable = false)
    private String paymentReference;

    @Column(name = "instruction_fingerprint", nullable = false, columnDefinition = "bpchar(64)", length = 64, updatable = false)
    private String instructionFingerprint;

    @Column(name = "amount", nullable = false, columnDefinition = "numeric", updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "beneficiary_name", nullable = false, length = 140, updatable = false)
    private String beneficiaryName;

    @Column(name = "beneficiary_account_number", nullable = false, length = 34, updatable = false)
    private String beneficiaryAccountNumber;

    @Column(name = "beneficiary_bank_bic", nullable = false, length = 11, updatable = false)
    private String beneficiaryBankBic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "reservation_token")
    private UUID reservationToken;

    @Column(name = "route_reference", length = 100)
    private String routeReference;

    @Column(name = "external_dispatch_reference", length = 100)
    private String externalDispatchReference;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason_code", length = 40)
    private PaymentReasonCode statusReasonCode;

    @Column(name = "status_reason_detail", length = 500)
    private String statusReasonDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {
    }

    public PaymentEntity(
            UUID paymentId,
            UUID sourceAccountId,
            String paymentReference,
            String instructionFingerprint,
            BigDecimal amount,
            String currency,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String beneficiaryBankBic,
            PaymentStatus status,
            UUID reservationToken,
            String routeReference,
            String externalDispatchReference,
            UUID ledgerTransactionId,
            PaymentReasonCode statusReasonCode,
            String statusReasonDetail,
            Instant createdAt,
            Instant updatedAt) {
        this.paymentId = paymentId;
        this.sourceAccountId = sourceAccountId;
        this.paymentReference = paymentReference;
        this.instructionFingerprint = instructionFingerprint;
        this.amount = amount;
        this.currency = currency;
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryAccountNumber = beneficiaryAccountNumber;
        this.beneficiaryBankBic = beneficiaryBankBic;
        this.status = status;
        this.reservationToken = reservationToken;
        this.routeReference = routeReference;
        this.externalDispatchReference = externalDispatchReference;
        this.ledgerTransactionId = ledgerTransactionId;
        this.statusReasonCode = statusReasonCode;
        this.statusReasonDetail = statusReasonDetail;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getInstructionFingerprint() {
        return instructionFingerprint;
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

    public String getBeneficiaryBankBic() {
        return beneficiaryBankBic;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public UUID getReservationToken() {
        return reservationToken;
    }

    public void setReservationToken(UUID reservationToken) {
        this.reservationToken = reservationToken;
    }

    public String getRouteReference() {
        return routeReference;
    }

    public void setRouteReference(String routeReference) {
        this.routeReference = routeReference;
    }

    public String getExternalDispatchReference() {
        return externalDispatchReference;
    }

    public void setExternalDispatchReference(String externalDispatchReference) {
        this.externalDispatchReference = externalDispatchReference;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public void setLedgerTransactionId(UUID ledgerTransactionId) {
        this.ledgerTransactionId = ledgerTransactionId;
    }

    public PaymentReasonCode getStatusReasonCode() {
        return statusReasonCode;
    }

    public void setStatusReasonCode(PaymentReasonCode statusReasonCode) {
        this.statusReasonCode = statusReasonCode;
    }

    public String getStatusReasonDetail() {
        return statusReasonDetail;
    }

    public void setStatusReasonDetail(String statusReasonDetail) {
        this.statusReasonDetail = statusReasonDetail;
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
