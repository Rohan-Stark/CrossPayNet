package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.contracts.LedgerPostingQueryPort;
import com.crosspaynet.ledger.contracts.LedgerPostingView;
import com.crosspaynet.ledger.domain.PostingFingerprint;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerEntryEntity;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionEntity;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionMapper;
import com.crosspaynet.ledger.infrastructure.persistence.LedgerTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerPostingQueryUnitTest {

    private LedgerTransactionRepository repository;
    private LedgerPostingQueryPort queryPort;

    @BeforeEach
    void setUp() {
        repository = mock(LedgerTransactionRepository.class);
        LedgerTransactionMapper mapper = new LedgerTransactionMapper();
        LedgerService service = new LedgerService(repository, mapper);
        queryPort = service;
    }

    @Test
    void shouldFindPostingByKeyAndMapTransactionIdAndAmount() {
        UUID txId = UUID.randomUUID();
        String postingKeyValue = "PAYMENT_SETTLEMENT:" + txId;
        LedgerPostingKey postingKey = LedgerPostingKey.of(postingKeyValue);
        Currency usd = new Currency("USD");
        Money amount = new Money(new BigDecimal("500.0000"), usd);

        LedgerAccountRef customerAccount = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, "ACC-1");
        LedgerAccountRef senderNostro = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO-1");

        List<LedgerEntryLine> lines = List.of(
                new LedgerEntryLine(customerAccount, EntryDirection.DEBIT, amount),
                new LedgerEntryLine(senderNostro, EntryDirection.CREDIT, amount)
        );

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                postingKey,
                usd,
                lines,
                "EXT-REF-1",
                "Description test"
        );
        PostingFingerprint fingerprint = PostingFingerprint.forStandard(command);

        LedgerTransactionEntity entity = new LedgerTransactionEntity(
                txId,
                postingKeyValue,
                LedgerTransactionType.STANDARD,
                "USD",
                new BigDecimal("500.0000"),
                2,
                "EXT-REF-1",
                "Description test",
                null,
                fingerprint.getValue(),
                Instant.now()
        );

        LedgerEntryEntity e1 = new LedgerEntryEntity(
                UUID.randomUUID(), 1, LedgerAccountType.BANKING_ACCOUNT, "ACC-1",
                EntryDirection.DEBIT, "USD", new BigDecimal("500.0000")
        );
        LedgerEntryEntity e2 = new LedgerEntryEntity(
                UUID.randomUUID(), 2, LedgerAccountType.INTERNAL, "NOSTRO-1",
                EntryDirection.CREDIT, "USD", new BigDecimal("500.0000")
        );
        entity.addEntry(e1);
        entity.addEntry(e2);

        when(repository.findByPostingKey(postingKeyValue)).thenReturn(Optional.of(entity));

        Optional<LedgerPostingView> result = queryPort.findPostingByKey(postingKeyValue);

        assertThat(result).isPresent();
        LedgerPostingView view = result.get();
        assertThat(view.transactionId()).isEqualTo(txId);
        assertThat(view.totalAmount()).isEqualTo(amount);
        assertThat(view.totalAmount().getCurrency()).isEqualTo(usd);
    }

    @Test
    void shouldReturnEmptyWhenPostingKeyNotFound() {
        String missingKey = "PAYMENT_SETTLEMENT:" + UUID.randomUUID();
        when(repository.findByPostingKey(missingKey)).thenReturn(Optional.empty());

        Optional<LedgerPostingView> result = queryPort.findPostingByKey(missingKey);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectNullPostingKeyThroughDomainValidation() {
        assertThatThrownBy(() -> queryPort.findPostingByKey(null))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("canonicalPostingKey cannot be null");
    }

    @Test
    void shouldRejectEmptyOrBlankPostingKeyThroughDomainValidation() {
        assertThatThrownBy(() -> queryPort.findPostingByKey(""))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("LedgerPostingKey cannot be null or empty");
    }

    @Test
    void shouldRejectDisallowedCharactersInPostingKeyThroughDomainValidation() {
        assertThatThrownBy(() -> queryPort.findPostingByKey("INVALID KEY WITH SPACES!"))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("contains disallowed characters");
    }

    @Test
    void shouldRejectOverlyLongPostingKeyThroughDomainValidation() {
        String longKey = "A".repeat(201);
        assertThatThrownBy(() -> queryPort.findPostingByKey(longKey))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("exceeds maximum");
    }

    @Test
    void shouldRejectReservedReversalPrefixInCallerPostingKeyThroughDomainValidation() {
        assertThatThrownBy(() -> queryPort.findPostingByKey("REVERSAL:12345"))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("cannot start with reserved prefix");
    }
}
