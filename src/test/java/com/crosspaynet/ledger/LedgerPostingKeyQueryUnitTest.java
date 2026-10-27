package com.crosspaynet.ledger;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
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

class LedgerPostingKeyQueryUnitTest {

    private LedgerTransactionRepository repository;
    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        repository = mock(LedgerTransactionRepository.class);
        LedgerTransactionMapper mapper = new LedgerTransactionMapper();
        ledgerService = new LedgerService(repository, mapper);
    }

    @Test
    void shouldFindTransactionByPostingKeyAndMapToView() {
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
                UUID.randomUUID(),
                1,
                LedgerAccountType.BANKING_ACCOUNT,
                "ACC-1",
                EntryDirection.DEBIT,
                "USD",
                new BigDecimal("500.0000")
        );
        LedgerEntryEntity e2 = new LedgerEntryEntity(
                UUID.randomUUID(),
                2,
                LedgerAccountType.INTERNAL,
                "NOSTRO-1",
                EntryDirection.CREDIT,
                "USD",
                new BigDecimal("500.0000")
        );
        entity.addEntry(e1);
        entity.addEntry(e2);

        when(repository.findByPostingKey(postingKeyValue)).thenReturn(Optional.of(entity));

        Optional<LedgerTransactionView> result = ledgerService.findByPostingKey(postingKey);

        assertThat(result).isPresent();
        LedgerTransactionView view = result.get();
        assertThat(view.postingKey()).isEqualTo(postingKey);
        assertThat(view.currency()).isEqualTo(new Currency("USD"));
        assertThat(view.totalAmount()).isEqualTo(new Money(new BigDecimal("500.0000"), new Currency("USD")));
        assertThat(view.entries()).hasSize(2);
    }

    @Test
    void shouldReturnEmptyForNonExistentPostingKey() {
        LedgerPostingKey missingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:MISSING");
        when(repository.findByPostingKey("PAYMENT_SETTLEMENT:MISSING")).thenReturn(Optional.empty());

        Optional<LedgerTransactionView> result = ledgerService.findByPostingKey(missingKey);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectNullPostingKey() {
        assertThatThrownBy(() -> ledgerService.findByPostingKey(null))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("postingKey cannot be null");
    }
}
