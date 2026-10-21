package com.crosspaynet.ledger.infrastructure.persistence;

import com.crosspaynet.ledger.LedgerTransactionType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ledger_transaction", schema = "ledger")
public class LedgerTransactionEntity implements Persistable<UUID> {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "posting_key", nullable = false, unique = true, length = 200, updatable = false)
    private String postingKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20, updatable = false)
    private LedgerTransactionType transactionType;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "total_amount", nullable = false, columnDefinition = "numeric", updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "entry_count", nullable = false, updatable = false)
    private int entryCount;

    @Column(name = "external_reference", nullable = false, length = 255, updatable = false)
    private String externalReference;

    @Column(name = "description", length = 500, updatable = false)
    private String description;

    @Column(name = "reversed_transaction_id", unique = true, updatable = false)
    private UUID reversedTransactionId;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "bpchar(64)", length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "transaction", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.EAGER)
    @OrderBy("lineNumber ASC")
    private List<LedgerEntryEntity> entries = new ArrayList<>();

    @Transient
    private boolean isNew = true;

    protected LedgerTransactionEntity() {
    }

    public LedgerTransactionEntity(
            UUID transactionId,
            String postingKey,
            LedgerTransactionType transactionType,
            String currency,
            BigDecimal totalAmount,
            int entryCount,
            String externalReference,
            String description,
            UUID reversedTransactionId,
            String requestFingerprint,
            Instant postedAt) {
        this.transactionId = transactionId;
        this.postingKey = postingKey;
        this.transactionType = transactionType;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.entryCount = entryCount;
        this.externalReference = externalReference;
        this.description = description;
        this.reversedTransactionId = reversedTransactionId;
        this.requestFingerprint = requestFingerprint;
        this.postedAt = postedAt;
    }

    public void addEntry(LedgerEntryEntity entry) {
        this.entries.add(entry);
        entry.setTransaction(this);
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getPostingKey() {
        return postingKey;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getDescription() {
        return description;
    }

    public UUID getReversedTransactionId() {
        return reversedTransactionId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public List<LedgerEntryEntity> getEntries() {
        return entries;
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
