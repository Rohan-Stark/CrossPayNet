package com.crosspaynet.ledger;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class LedgerPostingKeyQueryIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Test
    void shouldFindTransactionByPostingKey() {
        String uniqueId = UUID.randomUUID().toString();
        LedgerPostingKey postingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:" + uniqueId);
        Currency usd = new Currency("USD");
        Money amount = new Money(new BigDecimal("250.0000"), usd);

        LedgerAccountRef senderNostro = LedgerAccountRef.of(LedgerAccountType.INTERNAL, "NOSTRO_DEUTDEFF");
        LedgerAccountRef customerAccount = LedgerAccountRef.of(LedgerAccountType.BANKING_ACCOUNT, UUID.randomUUID().toString());

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                postingKey,
                usd,
                List.of(
                        new LedgerEntryLine(customerAccount, EntryDirection.DEBIT, amount),
                        new LedgerEntryLine(senderNostro, EntryDirection.CREDIT, amount)
                ),
                "EXT-REF-" + uniqueId,
                "Settlement posting test"
        );

        LedgerPostingResult postResult = ledgerService.postTransaction(command);
        assertThat(postResult.outcome()).isEqualTo(PostingOutcome.POSTED);

        Optional<LedgerTransactionView> foundOpt = ledgerService.findByPostingKey(postingKey);
        assertThat(foundOpt).isPresent();
        LedgerTransactionView view = foundOpt.get();
        assertThat(view.postingKey()).isEqualTo(postingKey);
        assertThat(view.totalAmount()).isEqualTo(amount);
        assertThat(view.entries()).hasSize(2);
        assertThat(view.transactionId()).isEqualTo(postResult.transactionId());
    }

    @Test
    void shouldReturnEmptyForNonExistentPostingKey() {
        LedgerPostingKey missingKey = LedgerPostingKey.of("PAYMENT_SETTLEMENT:NON_EXISTENT_" + UUID.randomUUID());
        Optional<LedgerTransactionView> foundOpt = ledgerService.findByPostingKey(missingKey);
        assertThat(foundOpt).isEmpty();
    }

    @Test
    void shouldRejectNullPostingKey() {
        assertThatThrownBy(() -> ledgerService.findByPostingKey(null))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("postingKey cannot be null");
    }
}
