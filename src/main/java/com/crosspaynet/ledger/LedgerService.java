package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.ledger.domain.LedgerEntry;
import com.crosspaynet.ledger.domain.LedgerTransaction;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionEntity;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionMapper;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Public application service facade for the Double-Entry Financial Ledger module (ADR-006).
 *
 * <p>Exposes synchronous operations for posting balanced transactions, executing full compensating reversals,
 * computing authoritative account balances on read, and retrieving read-only transaction views without leaking
 * internal domain or persistence types across module boundaries.
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository repository;
    private final LedgerTransactionMapper mapper;

    public LedgerService(LedgerTransactionRepository repository, LedgerTransactionMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * Atomically validates and persists a balanced, single-currency {@link LedgerTransactionType#STANDARD} transaction.
     *
     * <p>All domain and command validation executes in memory before attempting the conflict-safe header insert
     * ({@code INSERT ... ON CONFLICT (posting_key) DO NOTHING}). No {@link LedgerValidationException} is ever thrown
     * after a row has been written by the current invocation, preserving caller transaction safety under
     * {@code Propagation.REQUIRED}.
     */
    @Transactional(propagation = Propagation.REQUIRED, noRollbackFor = LedgerValidationException.class)
    public LedgerPostingResult postTransaction(PostLedgerTransactionCommand command) {
        if (command == null) {
            throw new LedgerValidationException("PostLedgerTransactionCommand cannot be null.");
        }

        LedgerTransaction candidate = LedgerTransaction.post(
                LedgerTransactionId.generate(),
                command,
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        int inserted = insertHeader(candidate);

        if (inserted == 1) {
            insertEntries(candidate);
            return new LedgerPostingResult(
                    candidate.getTransactionId(),
                    PostingOutcome.POSTED,
                    candidate.getPostedAt()
            );
        }

        LedgerTransactionEntity existingEntity = repository.findByPostingKey(candidate.getPostingKey().getValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Posting key conflict occurred for '" + candidate.getPostingKey()
                                + "', but existing transaction could not be loaded."
                ));
        LedgerTransaction existing = mapper.toDomain(existingEntity);

        if (existing.getRequestFingerprint().equals(candidate.getRequestFingerprint())) {
            return new LedgerPostingResult(
                    existing.getTransactionId(),
                    PostingOutcome.ALREADY_POSTED,
                    existing.getPostedAt()
            );
        }

        throw new LedgerPostingKeyConflictException(candidate.getPostingKey(), existing.getTransactionId());
    }

    /**
     * Atomically creates an immutable compensating {@link LedgerTransactionType#REVERSAL} transaction whose entries
     * mirror an existing posted {@link LedgerTransactionType#STANDARD} transaction 1-for-1 with flipped directions.
     *
     * <p>Claims the deterministic key {@code REVERSAL:{originalTransactionId}} via
     * {@code INSERT ... ON CONFLICT (posting_key) DO NOTHING} so repeated or conflicting reversal requests never
     * abort the caller's PostgreSQL transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED, noRollbackFor = LedgerValidationException.class)
    public LedgerPostingResult reverseTransaction(ReverseLedgerTransactionCommand command) {
        if (command == null) {
            throw new LedgerValidationException("ReverseLedgerTransactionCommand cannot be null.");
        }

        LedgerTransactionEntity originalEntity = repository.findById(command.originalTransactionId().getValue())
                .orElseThrow(() -> new LedgerTransactionNotFoundException(command.originalTransactionId()));
        LedgerTransaction original = mapper.toDomain(originalEntity);

        LedgerTransaction candidateReversal = original.reverse(
                LedgerTransactionId.generate(),
                command,
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
        int inserted = insertHeader(candidateReversal);

        if (inserted == 1) {
            insertEntries(candidateReversal);
            return new LedgerPostingResult(
                    candidateReversal.getTransactionId(),
                    PostingOutcome.POSTED,
                    candidateReversal.getPostedAt()
            );
        }

        LedgerTransactionEntity existingReversalEntity = repository
                .findByPostingKey(candidateReversal.getPostingKey().getValue())
                .or(() -> repository.findByReversedTransactionId(original.getTransactionId().getValue()))
                .orElseThrow(() -> new IllegalStateException(
                        "Reversal conflict occurred for original transaction '" + original.getTransactionId()
                                + "', but existing reversal transaction could not be loaded."
                ));
        LedgerTransaction existingReversal = mapper.toDomain(existingReversalEntity);

        if (existingReversal.getRequestFingerprint().equals(candidateReversal.getRequestFingerprint())) {
            return new LedgerPostingResult(
                    existingReversal.getTransactionId(),
                    PostingOutcome.ALREADY_POSTED,
                    existingReversal.getPostedAt()
            );
        }

        throw new LedgerReversalConflictException(
                command.originalTransactionId(),
                existingReversal.getTransactionId()
        );
    }

    /**
     * Computes the authoritative accounting balance ({@code debitTotal}, {@code creditTotal}, and signed
     * {@code netBalance = debitTotal - creditTotal}) for an account and currency on read.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public LedgerBalance getBalance(LedgerAccountRef account, Currency currency) {
        return repository.calculateBalance(account, currency);
    }

    /**
     * Retrieves a read-only view of a posted transaction and its immutable entry lines by ID.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<LedgerTransactionView> findTransaction(LedgerTransactionId transactionId) {
        if (transactionId == null) {
            throw new LedgerValidationException("transactionId cannot be null.");
        }
        return repository.findById(transactionId.getValue())
                .map(mapper::toDomain)
                .map(LedgerTransaction::toView);
    }

    /**
     * Retrieves a read-only view of a posted transaction and its immutable entry lines by posting key.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<LedgerTransactionView> findByPostingKey(LedgerPostingKey postingKey) {
        if (postingKey == null) {
            throw new LedgerValidationException("postingKey cannot be null.");
        }
        return repository.findByPostingKey(postingKey.getValue())
                .map(mapper::toDomain)
                .map(LedgerTransaction::toView);
    }

    private int insertHeader(LedgerTransaction transaction) {
        UUID reversedTxId = transaction.getReversedTransactionId() != null
                ? transaction.getReversedTransactionId().getValue()
                : null;

        return repository.insertHeaderOnConflictDoNothing(
                transaction.getTransactionId().getValue(),
                transaction.getPostingKey().getValue(),
                transaction.getTransactionType().name(),
                transaction.getCurrency().getCode(),
                transaction.getTotalAmount().getAmount(),
                transaction.getEntries().size(),
                transaction.getExternalReference(),
                transaction.getDescription(),
                reversedTxId,
                transaction.getRequestFingerprint().getValue(),
                transaction.getPostedAt()
        );
    }

    private void insertEntries(LedgerTransaction transaction) {
        UUID txId = transaction.getTransactionId().getValue();
        for (LedgerEntry entry : transaction.getEntries()) {
            repository.insertEntry(
                    entry.getEntryId(),
                    txId,
                    entry.getLineNumber(),
                    entry.getAccount().type().name(),
                    entry.getAccount().value(),
                    entry.getDirection().name(),
                    entry.getAmount().getCurrency().getCode(),
                    entry.getAmount().getAmount()
            );
        }
    }
}
