package com.crosspaynet.ledger.infrastructure.persistence;

import com.crosspaynet.ledger.EntryDirection;
import com.crosspaynet.ledger.LedgerAccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry", schema = "ledger")
public class LedgerEntryEntity implements Persistable<UUID> {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransactionEntity transaction;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30, updatable = false)
    private LedgerAccountType accountType;

    @Column(name = "account_reference", nullable = false, length = 100, updatable = false)
    private String accountReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private EntryDirection direction;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "amount", nullable = false, columnDefinition = "numeric", updatable = false)
    private BigDecimal amount;

    @Transient
    private boolean isNew = true;

    protected LedgerEntryEntity() {
    }

    public LedgerEntryEntity(
            UUID entryId,
            int lineNumber,
            LedgerAccountType accountType,
            String accountReference,
            EntryDirection direction,
            String currency,
            BigDecimal amount) {
        this.entryId = entryId;
        this.lineNumber = lineNumber;
        this.accountType = accountType;
        this.accountReference = accountReference;
        this.direction = direction;
        this.currency = currency;
        this.amount = amount;
    }

    void setTransaction(LedgerTransactionEntity transaction) {
        this.transaction = transaction;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public LedgerTransactionEntity getTransaction() {
        return transaction;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public LedgerAccountType getAccountType() {
        return accountType;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public UUID getId() {
        return entryId;
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
