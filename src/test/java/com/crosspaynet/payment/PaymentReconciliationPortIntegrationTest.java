package com.crosspaynet.payment;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReconciliationCursor;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationPort;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class PaymentReconciliationPortIntegrationTest {

    @Autowired
    private PaymentReconciliationPort paymentReconciliationPort;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AccountId testAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM payment.payment");
        UUID customerId = UUID.randomUUID();
        UUID accountUuid = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?) ON CONFLICT DO NOTHING", customerId);
        jdbcTemplate.update("""
            INSERT INTO banking.account (
                account_id, customer_id, currency, status, available_balance, reserved_amount, version
            ) VALUES (?, ?, 'USD', 'ACTIVE', 50000.0000, 0.0000, 0)
            """, accountUuid, customerId);
        testAccountId = new AccountId(accountUuid);
    }

    @Test
    void shouldPaginatePaymentsDeterministicallyWithKeysetContinuation() {
        Instant baseTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        List<PaymentView> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PaymentView pv = paymentService.initiatePayment(new InitiatePaymentCommand(
                    testAccountId,
                    PaymentReference.of("REF-RECON-" + UUID.randomUUID().toString().substring(0, 8)),
                    new BeneficiaryDetails("Beneficiary " + i, "ACC" + i, BicCode.of("DEUTDEFFXXX")),
                    new Money(new BigDecimal("100.0000"), new Currency("USD"))
            ));
            created.add(pv);

            Instant updatedTime = baseTime.plus(i * 10, ChronoUnit.MINUTES);
            jdbcTemplate.update(
                    "UPDATE payment.payment SET created_at = ?, updated_at = ? WHERE payment_id = ?",
                    Timestamp.from(updatedTime),
                    Timestamp.from(updatedTime),
                    pv.paymentId().getValue()
            );
        }

        Instant cutoffTime = baseTime.plus(1, ChronoUnit.HOURS);
        Set<PaymentStatus> statuses = Set.of(PaymentStatus.ROUTING);

        PaymentReconciliationPage page1 = paymentReconciliationPort.findPaymentsForReconciliation(
                statuses,
                cutoffTime,
                null,
                2
        );

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isPresent();
        assertThat(page1.items().get(0).paymentId()).isEqualTo(created.get(0).paymentId());
        assertThat(page1.items().get(1).paymentId()).isEqualTo(created.get(1).paymentId());

        PaymentReconciliationCursor cursor1 = page1.nextCursor().orElseThrow();
        PaymentReconciliationPage page2 = paymentReconciliationPort.findPaymentsForReconciliation(
                statuses,
                cutoffTime,
                cursor1,
                2
        );

        assertThat(page2.items()).hasSize(2);
        assertThat(page2.hasMore()).isTrue();
        assertThat(page2.nextCursor()).isPresent();
        assertThat(page2.items().get(0).paymentId()).isEqualTo(created.get(2).paymentId());
        assertThat(page2.items().get(1).paymentId()).isEqualTo(created.get(3).paymentId());

        PaymentReconciliationCursor cursor2 = page2.nextCursor().orElseThrow();
        PaymentReconciliationPage page3 = paymentReconciliationPort.findPaymentsForReconciliation(
                statuses,
                cutoffTime,
                cursor2,
                2
        );

        assertThat(page3.items()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
        assertThat(page3.nextCursor()).isEmpty();
        assertThat(page3.items().get(0).paymentId()).isEqualTo(created.get(4).paymentId());
    }

    @Test
    void shouldFilterByCutoffTimeAndStatus() {
        Instant baseTime = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        PaymentView p1 = paymentService.initiatePayment(new InitiatePaymentCommand(
                testAccountId,
                PaymentReference.of("REF-OLD-" + UUID.randomUUID().toString().substring(0, 8)),
                new BeneficiaryDetails("Alice", "ACC1", BicCode.of("DEUTDEFFXXX")),
                new Money(new BigDecimal("10.0000"), new Currency("USD"))
        ));
        Instant oldTime = baseTime.minus(30, ChronoUnit.MINUTES);
        jdbcTemplate.update("UPDATE payment.payment SET created_at = ?, updated_at = ? WHERE payment_id = ?",
                Timestamp.from(oldTime), Timestamp.from(oldTime), p1.paymentId().getValue());

        PaymentView p2 = paymentService.initiatePayment(new InitiatePaymentCommand(
                testAccountId,
                PaymentReference.of("REF-NEW-" + UUID.randomUUID().toString().substring(0, 8)),
                new BeneficiaryDetails("Bob", "ACC2", BicCode.of("DEUTDEFFXXX")),
                new Money(new BigDecimal("20.0000"), new Currency("USD"))
        ));
        Instant newTime = baseTime.plus(30, ChronoUnit.MINUTES);
        jdbcTemplate.update("UPDATE payment.payment SET created_at = ?, updated_at = ? WHERE payment_id = ?",
                Timestamp.from(newTime), Timestamp.from(newTime), p2.paymentId().getValue());

        PaymentView p3 = paymentService.initiatePayment(new InitiatePaymentCommand(
                testAccountId,
                PaymentReference.of("REF-CANC-" + UUID.randomUUID().toString().substring(0, 8)),
                new BeneficiaryDetails("Charlie", "ACC3", BicCode.of("DEUTDEFFXXX")),
                new Money(new BigDecimal("30.0000"), new Currency("USD"))
        ));
        paymentService.cancelPayment(new CancelPaymentCommand(p3.paymentId(), "Cancelled by customer"));
        jdbcTemplate.update("UPDATE payment.payment SET created_at = ?, updated_at = ? WHERE payment_id = ?",
                Timestamp.from(oldTime), Timestamp.from(oldTime), p3.paymentId().getValue());

        Instant cutoff = baseTime;
        PaymentReconciliationPage result = paymentReconciliationPort.findPaymentsForReconciliation(
                Set.of(PaymentStatus.ROUTING),
                cutoff,
                null,
                10
        );

        List<PaymentId> ids = result.items().stream().map(PaymentReconciliationRecord::paymentId).toList();
        assertThat(ids).contains(p1.paymentId());
        assertThat(ids).doesNotContain(p2.paymentId());
        assertThat(ids).doesNotContain(p3.paymentId());
    }

    @Test
    void shouldTieBreakDeterministicallyOnIdenticalUpdatedAt() {
        Instant identicalTime = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        PaymentView p1 = paymentService.initiatePayment(new InitiatePaymentCommand(
                testAccountId,
                PaymentReference.of("REF-TB1-" + UUID.randomUUID().toString().substring(0, 8)),
                new BeneficiaryDetails("User 1", "ACC1", BicCode.of("DEUTDEFFXXX")),
                new Money(new BigDecimal("15.0000"), new Currency("USD"))
        ));
        PaymentView p2 = paymentService.initiatePayment(new InitiatePaymentCommand(
                testAccountId,
                PaymentReference.of("REF-TB2-" + UUID.randomUUID().toString().substring(0, 8)),
                new BeneficiaryDetails("User 2", "ACC2", BicCode.of("DEUTDEFFXXX")),
                new Money(new BigDecimal("25.0000"), new Currency("USD"))
        ));

        jdbcTemplate.update("UPDATE payment.payment SET created_at = ?, updated_at = ? WHERE payment_id IN (?, ?)",
                Timestamp.from(identicalTime), Timestamp.from(identicalTime), p1.paymentId().getValue(), p2.paymentId().getValue());

        PaymentReconciliationPage page1 = paymentReconciliationPort.findPaymentsForReconciliation(
                Set.of(PaymentStatus.ROUTING),
                identicalTime.plus(1, ChronoUnit.MINUTES),
                null,
                1
        );

        assertThat(page1.items()).hasSize(1);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isPresent();

        PaymentReconciliationPage page2 = paymentReconciliationPort.findPaymentsForReconciliation(
                Set.of(PaymentStatus.ROUTING),
                identicalTime.plus(1, ChronoUnit.MINUTES),
                page1.nextCursor().orElseThrow(),
                1
        );

        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).paymentId()).isNotEqualTo(page1.items().get(0).paymentId());
    }

    @Test
    void shouldEnforceInputValidation() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> paymentReconciliationPort.findPaymentsForReconciliation(null, now, null, 10))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("statuses cannot be null or empty");

        assertThatThrownBy(() -> paymentReconciliationPort.findPaymentsForReconciliation(Set.of(), now, null, 10))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("statuses cannot be null or empty");

        assertThatThrownBy(() -> paymentReconciliationPort.findPaymentsForReconciliation(Set.of(PaymentStatus.COMPLIANCE_HOLD), null, null, 10))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("cutoffTime cannot be null");

        assertThatThrownBy(() -> paymentReconciliationPort.findPaymentsForReconciliation(Set.of(PaymentStatus.COMPLIANCE_HOLD), now, null, 0))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("limit must be at least 1");
    }
}
