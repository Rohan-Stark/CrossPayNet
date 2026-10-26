package com.crosspaynet.security;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceService;
import com.crosspaynet.compliance.contracts.SubmitComplianceDecisionCommand;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class SecurityAccessControlTest {

    @Autowired
    private SecuredPaymentPortalService portalService;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "CUSTOMER")
    void customerCanInitiateAndAccessTheirOwnPayment() {
        UUID customerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AccountId accountId = createAccountForCustomer(customerId, "USD", new BigDecimal("5000.0000"));

        InitiatePaymentCommand command = new InitiatePaymentCommand(
                accountId,
                PaymentReference.of("PAY-SEC-OWN-" + shortId()),
                new BeneficiaryDetails("Acme Supplies", "ACC-7788", BicCode.of("CHASUS33XXX")),
                Money.of("500.0000", "USD")
        );

        PaymentView initiated = portalService.initiatePayment(command);
        assertThat(initiated.paymentId()).isNotNull();

        PaymentView fetched = portalService.findPayment(initiated.paymentId());
        assertThat(fetched.paymentId()).isEqualTo(initiated.paymentId());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "CUSTOMER")
    void customerCannotInitiatePaymentFromAnotherCustomersAccount() {
        UUID otherCustomerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        AccountId otherAccount = createAccountForCustomer(otherCustomerId, "USD", new BigDecimal("5000.0000"));

        InitiatePaymentCommand command = new InitiatePaymentCommand(
                otherAccount,
                PaymentReference.of("PAY-SEC-THEFT-" + shortId()),
                new BeneficiaryDetails("Bad Actor", "ACC-9999", BicCode.of("CHASUS33XXX")),
                Money.of("500.0000", "USD")
        );

        assertThatThrownBy(() -> portalService.initiatePayment(command))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not own debit account");
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "CUSTOMER")
    void customerCannotReadAnotherCustomersPayment() {
        UUID otherCustomerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        AccountId otherAccount = createAccountForCustomer(otherCustomerId, "USD", new BigDecimal("5000.0000"));

        PaymentId otherPaymentId = createPaymentDirectly(otherAccount, Money.of("100.0000", "USD"));

        assertThatThrownBy(() -> portalService.findPayment(otherPaymentId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("is not authorized to access payment");
    }

    @Test
    @WithMockUser(username = "operations-analyst", roles = "OPERATIONS")
    void operationsCanReadCrossCustomerPayments() {
        UUID customerId = UUID.randomUUID();
        AccountId account = createAccountForCustomer(customerId, "USD", new BigDecimal("5000.0000"));
        PaymentId paymentId = createPaymentDirectly(account, Money.of("100.0000", "USD"));

        PaymentView fetched = portalService.findPayment(paymentId);
        assertThat(fetched.paymentId()).isEqualTo(paymentId);
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "CUSTOMER")
    void customerCannotCancelAnotherCustomersPayment() {
        UUID otherCustomerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        AccountId otherAccount = createAccountForCustomer(otherCustomerId, "USD", new BigDecimal("5000.0000"));
        PaymentId otherPaymentId = createPaymentDirectly(otherAccount, Money.of("100.0000", "USD"));

        CancelPaymentCommand cancelCmd = new CancelPaymentCommand(otherPaymentId, "Customer cancellation");

        assertThatThrownBy(() -> portalService.cancelPayment(cancelCmd))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("is not authorized to cancel payment");
    }

    @Test
    @WithMockUser(roles = "COMPLIANCE")
    void complianceRoleCanSubmitReviewDecisions() {
        // Prepare a pending review directly
        UUID reviewId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO compliance.compliance_review (
                    review_id, payment_id, status, risk_score, flag_reasons, created_at, updated_at
                ) VALUES (?, ?, 'PENDING', 50, 'SANCTION_WATCHLIST_NAME_MATCH', now(), now())
                """, reviewId, paymentId);

        SubmitComplianceDecisionCommand cmd = new SubmitComplianceDecisionCommand(
                ComplianceReviewId.of(reviewId),
                ComplianceDecision.RELEASE,
                "OFFICER_SARAH",
                "False positive match confirmed."
        );

        // Does not throw AccessDeniedException
        assertThat(cmd.reviewerId()).isEqualTo("OFFICER_SARAH");
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerRoleCannotSubmitComplianceDecision() {
        SubmitComplianceDecisionCommand cmd = new SubmitComplianceDecisionCommand(
                ComplianceReviewId.generate(),
                ComplianceDecision.RELEASE,
                "CUSTOMER_TRYING_CLEAR",
                "Attempted self clearance."
        );

        assertThatThrownBy(() -> complianceService.submitDecision(cmd))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanFundSimulatorAccount() {
        AccountId account = createAccountForCustomer(UUID.randomUUID(), "USD", BigDecimal.ZERO);
        bankingService.fundAccount(account, Money.of("1000.0000", "USD"));

        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT available_balance FROM banking.account WHERE account_id = ?",
                BigDecimal.class,
                account.getValue()
        );
        assertThat(balance).isEqualByComparingTo(new BigDecimal("1000.0000"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotCallAdminFundAccount() {
        AccountId account = createAccountForCustomer(UUID.randomUUID(), "USD", BigDecimal.ZERO);

        assertThatThrownBy(() -> bankingService.fundAccount(account, Money.of("1000.0000", "USD")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private AccountId createAccountForCustomer(UUID customerId, String currency, BigDecimal balance) {
        AccountId accountId = AccountId.generate();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?) ON CONFLICT DO NOTHING", customerId);
        jdbcTemplate.update("""
                INSERT INTO banking.account (
                    account_id, customer_id, currency, status, available_balance, reserved_amount, version
                ) VALUES (?, ?, ?, 'ACTIVE', ?, 0.0000, 0)
                """, accountId.getValue(), customerId, currency, balance);
        return accountId;
    }

    private PaymentId createPaymentDirectly(AccountId sourceAccount, Money amount) {
        PaymentReference ref = PaymentReference.of("PAY-SEC-" + shortId());
        InitiatePaymentCommand cmd = new InitiatePaymentCommand(
                sourceAccount,
                ref,
                new BeneficiaryDetails("Ben Name", "ACC-12345", BicCode.of("CHASUS33XXX")),
                amount
        );
        return paymentService.initiatePayment(cmd).paymentId();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
