package com.crosspaynet.banking;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.contracts.ReservationView;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class BankingReservationQueryIntegrationTest {

    @Autowired
    private BankingService bankingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AccountId testAccountId;

    @BeforeEach
    void setUp() {
        UUID customerId = UUID.randomUUID();
        UUID accountUuid = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?) ON CONFLICT DO NOTHING", customerId);
        jdbcTemplate.update("""
            INSERT INTO banking.account (
                account_id, customer_id, currency, status, available_balance, reserved_amount, version
            ) VALUES (?, ?, 'USD', 'ACTIVE', 10000.0000, 0.0000, 0)
            """, accountUuid, customerId);
        testAccountId = new AccountId(accountUuid);
    }

    @Test
    void shouldFindReservationByTokenAcrossLifecycleTransitions() {
        PaymentReference ref = PaymentReference.of("REF-RES-" + UUID.randomUUID().toString().substring(0, 8));
        Money amount = new Money(new BigDecimal("150.0000"), new Currency("USD"));

        ReservationToken token = bankingService.reserveFunds(testAccountId, amount, ref);
        assertThat(token).isNotNull();

        Optional<ReservationView> initialOpt = bankingService.findReservation(token);
        assertThat(initialOpt).isPresent();
        ReservationView initialView = initialOpt.get();
        assertThat(initialView.reservationToken()).isEqualTo(token);
        assertThat(initialView.accountId()).isEqualTo(testAccountId);
        assertThat(initialView.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(initialView.amount()).isEqualTo(amount);
        assertThat(initialView.paymentReference()).isEqualTo(ref);

        bankingService.releaseReservation(testAccountId, token);
        Optional<ReservationView> releasedOpt = bankingService.findReservation(token);
        assertThat(releasedOpt).isPresent();
        assertThat(releasedOpt.get().status()).isEqualTo(ReservationStatus.RELEASED);

        PaymentReference ref2 = PaymentReference.of("REF-RES2-" + UUID.randomUUID().toString().substring(0, 8));
        ReservationToken token2 = bankingService.reserveFunds(testAccountId, amount, ref2);
        bankingService.consumeReservation(testAccountId, token2);

        Optional<ReservationView> consumedOpt = bankingService.findReservation(token2);
        assertThat(consumedOpt).isPresent();
        assertThat(consumedOpt.get().status()).isEqualTo(ReservationStatus.CONSUMED);
    }

    @Test
    void shouldReturnEmptyForUnknownReservationToken() {
        ReservationToken unknownToken = ReservationToken.generate();
        Optional<ReservationView> view = bankingService.findReservation(unknownToken);
        assertThat(view).isEmpty();
    }

    @Test
    void shouldRejectNullReservationToken() {
        assertThatThrownBy(() -> bankingService.findReservation(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ReservationToken cannot be null");
    }
}
