package com.crosspaynet.compliance;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.compliance.contracts.ComplianceConflictException;
import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewView;
import com.crosspaynet.compliance.contracts.ComplianceService;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.contracts.ScreenPaymentCommand;
import com.crosspaynet.compliance.contracts.SubmitComplianceDecisionCommand;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class ComplianceServiceIntegrationTest {

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cleanPaymentShouldBeScreenedAndClearedAutomaticallyTransitioningToRouting() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("5000.0000"));
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-CLEAN-" + shortId()),
                new BeneficiaryDetails("Legitimate Merchant LLC", "ACC-1234", BicCode.of("CHASUS33XXX")),
                Money.of("500.0000", "USD")
        );

        PaymentView initiated = paymentService.initiatePayment(command);

        // Verification of review record
        Optional<ComplianceReviewView> reviewOpt = complianceService.findReviewByPaymentId(initiated.paymentId());
        assertThat(reviewOpt).isPresent();
        ComplianceReviewView review = reviewOpt.get();
        assertThat(review.status()).isEqualTo(ComplianceStatus.CLEARED);
        assertThat(review.decision()).isEqualTo(ComplianceDecision.RELEASE);
        assertThat(review.riskScore()).isEqualTo(0);

        // Payment must be advanced to ROUTING
        PaymentView payment = paymentService.findPayment(initiated.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.ROUTING);
    }

    @Test
    void flaggedSanctionsPaymentShouldPauseInComplianceHoldWithPendingReview() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("5000.0000"));
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-FLAG-NAME-" + shortId()),
                new BeneficiaryDetails("Global Terror Supplies", "ACC-9999", BicCode.of("CHASUS33XXX")),
                Money.of("500.0000", "USD")
        );

        PaymentView initiated = paymentService.initiatePayment(command);

        // Verification of review record
        Optional<ComplianceReviewView> reviewOpt = complianceService.findReviewByPaymentId(initiated.paymentId());
        assertThat(reviewOpt).isPresent();
        ComplianceReviewView review = reviewOpt.get();
        assertThat(review.status()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(review.decision()).isNull();
        assertThat(review.riskScore()).isGreaterThanOrEqualTo(50);
        assertThat(review.flagReasons()).anyMatch(r -> r.contains("SANCTION_WATCHLIST_NAME_MATCH"));

        // Payment remains in COMPLIANCE_HOLD with active reservation
        PaymentView payment = paymentService.findPayment(initiated.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);

        BigDecimal reservedAmount = jdbcTemplate.queryForObject(
                "SELECT reserved_amount FROM banking.account WHERE account_id = ?",
                BigDecimal.class,
                accountId.getValue()
        );
        assertThat(reservedAmount).isEqualByComparingTo(new BigDecimal("500.0000"));
    }

    @Test
    void flaggedHighValueUsdPaymentShouldPauseInComplianceHold() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("50000.0000"));
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-HIGH-VAL-" + shortId()),
                new BeneficiaryDetails("Legitimate Merchant LLC", "ACC-1234", BicCode.of("CHASUS33XXX")),
                Money.of("15000.0000", "USD")
        );

        PaymentView initiated = paymentService.initiatePayment(command);

        Optional<ComplianceReviewView> reviewOpt = complianceService.findReviewByPaymentId(initiated.paymentId());
        assertThat(reviewOpt).isPresent();
        assertThat(reviewOpt.get().status()).isEqualTo(ComplianceStatus.PENDING);
        assertThat(reviewOpt.get().flagReasons()).anyMatch(r -> r.contains("HIGH_VALUE_TRANSACTION_THRESHOLD"));

        PaymentView payment = paymentService.findPayment(initiated.paymentId()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLIANCE_HOLD);
    }

    @Test
    @WithMockUser(roles = "COMPLIANCE")
    void manualReleaseDecisionShouldTransitionReviewToClearedAndPaymentToRouting() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("50000.0000"));
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-MAN-REL-" + shortId()),
                new BeneficiaryDetails("Acme High Value Client", "ACC-5555", BicCode.of("CHASUS33XXX")),
                Money.of("20000.0000", "USD")
        );

        PaymentView initiated = paymentService.initiatePayment(command);
        ComplianceReviewView pendingReview = complianceService.findReviewByPaymentId(initiated.paymentId()).orElseThrow();

        // Compliance Officer reviews and approves release
        SubmitComplianceDecisionCommand decisionCmd = new SubmitComplianceDecisionCommand(
                pendingReview.reviewId(),
                ComplianceDecision.RELEASE,
                "OFFICER_EMILY",
                "Verified valid commercial trade invoice and verified KYC documentation."
        );

        ComplianceReviewView decidedReview = complianceService.submitDecision(decisionCmd);
        assertThat(decidedReview.status()).isEqualTo(ComplianceStatus.CLEARED);
        assertThat(decidedReview.decision()).isEqualTo(ComplianceDecision.RELEASE);
        assertThat(decidedReview.reviewerId()).isEqualTo("OFFICER_EMILY");

        PaymentView updatedPayment = paymentService.findPayment(initiated.paymentId()).orElseThrow();
        assertThat(updatedPayment.status()).isEqualTo(PaymentStatus.ROUTING);

        // Idempotent replay with same inputs returns existing review without re-executing transitions
        ComplianceReviewView replay = complianceService.submitDecision(decisionCmd);
        assertThat(replay).isEqualTo(decidedReview);
    }

    @Test
    @WithMockUser(roles = "COMPLIANCE")
    void manualRejectDecisionShouldTransitionReviewToRejectedAndReleaseReservationAtomically() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("5000.0000"));
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-MAN-REJ-" + shortId()),
                new BeneficiaryDetails("Suspected Terror Affiliate", "ACC-8888", BicCode.of("CHASUS33XXX")),
                Money.of("1000.0000", "USD")
        );

        PaymentView initiated = paymentService.initiatePayment(command);
        ComplianceReviewView pendingReview = complianceService.findReviewByPaymentId(initiated.paymentId()).orElseThrow();

        // Compliance Officer rejects payment
        SubmitComplianceDecisionCommand decisionCmd = new SubmitComplianceDecisionCommand(
                pendingReview.reviewId(),
                ComplianceDecision.REJECT,
                "OFFICER_JAMES",
                "True positive watchlist match confirmed against simulated sanctions database."
        );

        ComplianceReviewView decidedReview = complianceService.submitDecision(decisionCmd);
        assertThat(decidedReview.status()).isEqualTo(ComplianceStatus.REJECTED);
        assertThat(decidedReview.decision()).isEqualTo(ComplianceDecision.REJECT);

        PaymentView updatedPayment = paymentService.findPayment(initiated.paymentId()).orElseThrow();
        assertThat(updatedPayment.status()).isEqualTo(PaymentStatus.REJECTED);

        // Reservation must be released and available balance restored in Banking atomically
        BigDecimal availableBalance = jdbcTemplate.queryForObject(
                "SELECT available_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class,
                accountId.getValue()
        );
        BigDecimal reservedAmount = jdbcTemplate.queryForObject(
                "SELECT reserved_amount FROM banking.account WHERE account_id = ?",
                BigDecimal.class,
                accountId.getValue()
        );
        assertThat(availableBalance).isEqualByComparingTo(new BigDecimal("5000.0000"));
        assertThat(reservedAmount).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @WithMockUser(roles = "COMPLIANCE")
    void conflictingDecisionShouldBeRejected() {
        AccountId accountId = createAccount("USD", "ACTIVE", new BigDecimal("50000.0000"));
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-CONFLICT-" + shortId()),
                new BeneficiaryDetails("Acme High Value Client", "ACC-5555", BicCode.of("CHASUS33XXX")),
                Money.of("20000.0000", "USD")
        );

        PaymentView initiated = paymentService.initiatePayment(command);
        ComplianceReviewView pendingReview = complianceService.findReviewByPaymentId(initiated.paymentId()).orElseThrow();

        SubmitComplianceDecisionCommand firstCmd = new SubmitComplianceDecisionCommand(
                pendingReview.reviewId(),
                ComplianceDecision.RELEASE,
                "OFFICER_A",
                "First approval note."
        );
        complianceService.submitDecision(firstCmd);

        // Conflicting reject attempt
        SubmitComplianceDecisionCommand conflictCmd = new SubmitComplianceDecisionCommand(
                pendingReview.reviewId(),
                ComplianceDecision.REJECT,
                "OFFICER_B",
                "Conflicting rejection attempt."
        );
        assertThatThrownBy(() -> complianceService.submitDecision(conflictCmd))
                .isInstanceOf(ComplianceConflictException.class);
    }

    private AccountId createAccount(String currency, String status, BigDecimal balance) {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = AccountId.generate();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?) ON CONFLICT DO NOTHING", customerId);
        jdbcTemplate.update("""
                INSERT INTO banking.account (
                    account_id, customer_id, currency, status, available_balance, reserved_amount, version
                ) VALUES (?, ?, ?, ?, ?, 0.0000, 0)
                """, accountId.getValue(), customerId, currency, status, balance);
        return accountId;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
