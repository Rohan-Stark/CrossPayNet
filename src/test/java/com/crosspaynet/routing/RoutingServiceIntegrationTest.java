package com.crosspaynet.routing;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.ledger.PostingOutcome;
import com.crosspaynet.messaging.MessagingService;
import com.crosspaynet.messaging.contracts.AcknowledgeMessageCommand;
import com.crosspaynet.messaging.contracts.DispatchPaymentMessageCommand;
import com.crosspaynet.messaging.contracts.PaymentMessageStatus;
import com.crosspaynet.messaging.contracts.PaymentMessageView;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CancelPaymentCommand;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.InvalidPaymentStateException;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.contracts.RejectPaymentCommand;
import com.crosspaynet.routing.contracts.NoValidRouteException;
import com.crosspaynet.routing.contracts.PaymentRoutingOutcome;
import com.crosspaynet.routing.contracts.PaymentRoutingResult;
import com.crosspaynet.routing.contracts.RegisterCorrespondentRouteCommand;
import com.crosspaynet.routing.contracts.RouteConflictException;
import com.crosspaynet.routing.contracts.RouteContext;
import com.crosspaynet.routing.contracts.RouteId;
import com.crosspaynet.routing.contracts.RouteNotFoundException;
import com.crosspaynet.routing.contracts.RouteStatus;
import com.crosspaynet.routing.contracts.RoutingValidationException;
import com.crosspaynet.routing.contracts.SelectRouteQuery;
import com.crosspaynet.settlement.SettlementService;
import com.crosspaynet.settlement.contracts.SettlePaymentCommand;
import com.crosspaynet.settlement.contracts.SettlementResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class RoutingServiceIntegrationTest {

    private static final BicCode SENDER_BIC = new BicCode("CHASUS33XXX");

    @Autowired
    private RoutingService routingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // 1. Route Registration, Idempotent Replay & Conflict Rejection
    // =========================================================================

    @Test
    void shouldRegisterRouteReplayIdempotentlyAndRejectRouteReferenceAndPriorityConflictsWithoutAbortingCallerTx() {
        BicCode destBic = uniqueBic("DE");
        String routeRef = "ROUTE_REG_" + shortId();
        RegisterCorrespondentRouteCommand cmd = new RegisterCorrespondentRouteCommand(
                routeRef,
                destBic,
                SENDER_BIC,
                new Currency("EUR"),
                "NOSTRO_EUR_" + shortId(),
                1
        );

        RouteContext registered = routingService.registerRoute(cmd);
        assertThat(registered.routeReference()).isEqualTo(routeRef);
        assertThat(registered.destinationBic()).isEqualTo(destBic);
        assertThat(registered.correspondentBic()).isEqualTo(SENDER_BIC);
        assertThat(registered.currency()).isEqualTo(new Currency("EUR"));
        assertThat(registered.priority()).isEqualTo(1);
        assertThat(registered.status()).isEqualTo(RouteStatus.ACTIVE);

        // Idempotent replay with identical attributes returns same RouteContext
        RouteContext replay = routingService.registerRoute(cmd);
        assertThat(replay).isEqualTo(registered);

        // Same routeReference with different priority or clearingAccountReference -> RouteConflictException
        RegisterCorrespondentRouteCommand conflictingRefCmd = new RegisterCorrespondentRouteCommand(
                routeRef,
                destBic,
                SENDER_BIC,
                new Currency("EUR"),
                "DIFFERENT_CLEARING_ACCOUNT",
                2
        );
        assertThatThrownBy(() -> routingService.registerRoute(conflictingRefCmd))
                .isInstanceOf(RouteConflictException.class);

        // Different routeReference claiming the same (destinationBic, currency, priority) -> RouteConflictException
        RegisterCorrespondentRouteCommand conflictingSlotCmd = new RegisterCorrespondentRouteCommand(
                "OTHER_" + routeRef,
                destBic,
                SENDER_BIC,
                new Currency("EUR"),
                "NOSTRO_EUR_OTHER",
                1
        );
        assertThatThrownBy(() -> routingService.registerRoute(conflictingSlotCmd))
                .isInstanceOf(RouteConflictException.class);

        // Verify RouteConflictException does not mark a caller's shared transaction rollback-only
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> routingService.registerRoute(conflictingRefCmd))
                    .isInstanceOf(RouteConflictException.class);
            assertThatThrownBy(() -> routingService.registerRoute(conflictingSlotCmd))
                    .isInstanceOf(RouteConflictException.class);
            assertThat(status.isRollbackOnly()).isFalse();
        });
    }

    // =========================================================================
    // 2. Deterministic Priority Selection, Deactivation/Activation Promotion & NoValidRouteException
    // =========================================================================

    @Test
    void shouldSelectHighestPriorityActiveRoutePromoteBackupOnDeactivationAndThrowNoValidRouteExceptionWhenNoneActive() {
        BicCode destBic = uniqueBic("FR");
        Currency eur = new Currency("EUR");

        RouteContext backupRoute = routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_FR_BACKUP_" + shortId(),
                destBic,
                new BicCode("CITIUS33XXX"),
                eur,
                "NOSTRO_EUR_CITI_02",
                20
        ));
        RouteContext primaryRoute = routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_FR_PRIMARY_" + shortId(),
                destBic,
                SENDER_BIC,
                eur,
                "NOSTRO_EUR_CHAS_01",
                10
        ));

        // 1. Lower numeric priority (10) wins over backup (20)
        RouteContext selected1 = routingService.selectRoute(new SelectRouteQuery(destBic, eur));
        assertThat(selected1).isEqualTo(primaryRoute);

        // 2. Deactivating primary route promotes backup route (priority 20)
        RouteContext deactivatedPrimary = routingService.deactivateRoute(primaryRoute.routeReference());
        assertThat(deactivatedPrimary.status()).isEqualTo(RouteStatus.INACTIVE);
        assertThat(routingService.deactivateRoute(primaryRoute.routeReference())).isEqualTo(deactivatedPrimary);

        RouteContext selected2 = routingService.selectRoute(new SelectRouteQuery(destBic, eur));
        assertThat(selected2).isEqualTo(backupRoute);

        // 3. Deactivating backup route leaves zero ACTIVE routes -> NoValidRouteException
        routingService.deactivateRoute(backupRoute.routeReference());
        assertThatThrownBy(() -> routingService.selectRoute(new SelectRouteQuery(destBic, eur)))
                .isInstanceOf(NoValidRouteException.class);

        // 4. Mismatched currency or BIC -> NoValidRouteException
        routingService.activateRoute(primaryRoute.routeReference());
        assertThat(routingService.selectRoute(new SelectRouteQuery(destBic, eur)).routeReference())
                .isEqualTo(primaryRoute.routeReference());
        assertThatThrownBy(() -> routingService.selectRoute(new SelectRouteQuery(destBic, new Currency("USD"))))
                .isInstanceOf(NoValidRouteException.class);

        // 5. findRoutesForDestination returns both active and inactive ordered by priority ASC
        List<RouteContext> allForDest = routingService.findRoutesForDestination(destBic, eur);
        assertThat(allForDest).extracting(RouteContext::routeReference)
                .containsExactly(primaryRoute.routeReference(), backupRoute.routeReference());
    }

    // =========================================================================
    // 3. Happy Path routePayment (ROUTING -> EXECUTING) + Full Downstream Messaging & Settlement
    // =========================================================================

    @Test
    void shouldRoutePaymentToExecutionAndCompleteDownstreamMessagingAndSettlementUsingResolvedRouteClearingAccount() {
        BicCode destBic = uniqueBic("GB");
        Currency gbp = new Currency("GBP");
        String clearingAccountRef = "NOSTRO_GBP_BARC_" + shortId();

        RouteContext registeredRoute = routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_GBP_BARC_" + shortId(),
                destBic,
                new BicCode("BARCGB22XXX"),
                gbp,
                clearingAccountRef,
                1
        ));

        AccountId sourceAccountId = createAccount("GBP", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView routingPayment = createRoutingPayment(
                sourceAccountId,
                destBic,
                Money.of("350.0000", "GBP")
        );

        // 1. Execute routePayment -> ROUTING -> EXECUTING at the Financial Commitment Point
        PaymentRoutingResult routingResult = routingService.routePayment(routingPayment.paymentId());
        assertThat(routingResult.outcome()).isEqualTo(PaymentRoutingOutcome.ROUTE_COMMITTED);
        assertThat(routingResult.selectedRoute()).contains(registeredRoute);
        assertThat(routingResult.payment().status()).isEqualTo(PaymentStatus.EXECUTING);
        assertThat(routingResult.payment().routeReference()).isEqualTo(registeredRoute.routeReference());

        // Persisted Payment state & active Banking reservation verified
        PaymentView persistedExecuting = paymentService.findPayment(routingPayment.paymentId()).orElseThrow();
        assertThat(persistedExecuting.status()).isEqualTo(PaymentStatus.EXECUTING);
        assertThat(persistedExecuting.routeReference()).isEqualTo(registeredRoute.routeReference());
        assertReservationStatus(routingPayment.reservationToken(), "ACTIVE", new BigDecimal("350.0000"), "GBP");
        assertAccountBalances(sourceAccountId, new BigDecimal("650.0000"), new BigDecimal("350.0000"));

        // 2. Downstream Messaging reads committed routeReference from PaymentView
        PaymentMessageView dispatchedMsg = messagingService.dispatchPaymentMessage(
                new DispatchPaymentMessageCommand(routingPayment.paymentId(), 1, SENDER_BIC)
        );
        assertThat(dispatchedMsg.routeReference()).isEqualTo(registeredRoute.routeReference());
        assertThat(dispatchedMsg.status()).isEqualTo(PaymentMessageStatus.SENT);

        PaymentMessageView acknowledgedMsg = messagingService.acknowledgeMessage(
                new AcknowledgeMessageCommand(dispatchedMsg.messageId(), "PACS002-ACSC-GBP-" + shortId())
        );
        assertThat(acknowledgedMsg.status()).isEqualTo(PaymentMessageStatus.ACKNOWLEDGED);

        // 3. Downstream caller resolves the committed route via RoutingService.findRouteByReference
        //    and passes its clearingAccountReference to SettlementService.settlePayment
        RouteContext resolvedRoute = routingService.findRouteByReference(persistedExecuting.routeReference())
                .orElseThrow();
        assertThat(resolvedRoute.clearingAccountReference()).isEqualTo(clearingAccountRef);

        SettlementResult settlementResult = settlementService.settlePayment(new SettlePaymentCommand(
                routingPayment.paymentId(),
                resolvedRoute.clearingAccountReference(),
                acknowledgedMsg.acknowledgmentReference()
        ));
        assertThat(settlementResult.ledgerPostingOutcome()).isEqualTo(PostingOutcome.POSTED);
        assertThat(settlementResult.payment().status()).isEqualTo(PaymentStatus.SETTLED);
        assertReservationStatus(routingPayment.reservationToken(), "CONSUMED", new BigDecimal("350.0000"), "GBP");
        assertAccountBalances(sourceAccountId, new BigDecimal("650.0000"), new BigDecimal("0.0000"));
    }

    // =========================================================================
    // 4. No-Route Rejection (ROUTING -> REJECTED + NO_ROUTE_AVAILABLE + Atomic Reservation Release)
    // =========================================================================

    @Test
    void shouldRejectPaymentWithNoRouteAvailableAndAtomicallyReleaseBankingReservationWhenNoActiveRouteMatches() {
        BicCode unroutableBic = uniqueBic("JP");
        AccountId sourceAccountId = createAccount("JPY", "ACTIVE", new BigDecimal("50000.0000"));
        PaymentView routingPayment = createRoutingPayment(
                sourceAccountId,
                unroutableBic,
                Money.of("12000.0000", "JPY")
        );
        assertAccountBalances(sourceAccountId, new BigDecimal("38000.0000"), new BigDecimal("12000.0000"));

        PaymentRoutingResult result = routingService.routePayment(routingPayment.paymentId());

        assertThat(result.outcome()).isEqualTo(PaymentRoutingOutcome.REJECTED_NO_ROUTE);
        assertThat(result.selectedRoute()).isEmpty();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(result.payment().statusReasonCode()).isEqualTo(PaymentReasonCode.NO_ROUTE_AVAILABLE);
        assertThat(result.payment().routeReference()).isNull();

        // Verify persisted Payment is REJECTED (NO_ROUTE_AVAILABLE) and Banking reservation is RELEASED
        PaymentView persistedRejected = paymentService.findPayment(routingPayment.paymentId()).orElseThrow();
        assertThat(persistedRejected.status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(persistedRejected.statusReasonCode()).isEqualTo(PaymentReasonCode.NO_ROUTE_AVAILABLE);
        assertReservationStatus(routingPayment.reservationToken(), "RELEASED", new BigDecimal("12000.0000"), "JPY");
        assertAccountBalances(sourceAccountId, new BigDecimal("50000.0000"), new BigDecimal("0.0000"));

        // Idempotent replay of routePayment on already-REJECTED (NO_ROUTE_AVAILABLE) payment
        PaymentRoutingResult replay = routingService.routePayment(routingPayment.paymentId());
        assertThat(replay).isEqualTo(result);
    }

    // =========================================================================
    // 5. Idempotent Replay of Committed Route Even After Route Deactivation
    // =========================================================================

    @Test
    void shouldReplayCommittedRouteIdempotentlyEvenAfterSelectedRouteIsDeactivatedAndBackupRouteIsActive() {
        BicCode destBic = uniqueBic("CH");
        Currency chf = new Currency("CHF");

        RouteContext primary = routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_CHF_PRIMARY_" + shortId(),
                destBic,
                SENDER_BIC,
                chf,
                "NOSTRO_CHF_01",
                1
        ));
        routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_CHF_BACKUP_" + shortId(),
                destBic,
                new BicCode("CITIUS33XXX"),
                chf,
                "NOSTRO_CHF_02",
                2
        ));

        AccountId sourceAccountId = createAccount("CHF", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView routingPayment = createRoutingPayment(
                sourceAccountId,
                destBic,
                Money.of("200.0000", "CHF")
        );

        // 1. Route payment -> commits primary route
        PaymentRoutingResult firstResult = routingService.routePayment(routingPayment.paymentId());
        assertThat(firstResult.outcome()).isEqualTo(PaymentRoutingOutcome.ROUTE_COMMITTED);
        assertThat(firstResult.selectedRoute()).contains(primary);

        // 2. Deactivate primary route so backup route is now top active route for new selections
        RouteContext deactivatedPrimary = routingService.deactivateRoute(primary.routeReference());
        assertThat(deactivatedPrimary.status()).isEqualTo(RouteStatus.INACTIVE);

        // 3. Replaying routePayment on the already-EXECUTING payment MUST return the original committed
        //    primary route (now INACTIVE) and MUST NOT switch to the backup route!
        PaymentRoutingResult replayResult = routingService.routePayment(routingPayment.paymentId());
        assertThat(replayResult.outcome()).isEqualTo(PaymentRoutingOutcome.ROUTE_COMMITTED);
        assertThat(replayResult.selectedRoute()).contains(deactivatedPrimary);
        assertThat(replayResult.payment().routeReference()).isEqualTo(primary.routeReference());

        // 4. If the committed routeReference on an EXECUTING payment cannot be resolved in routing.correspondent_route,
        //    surface RouteConflictException rather than silently picking another route
        jdbcTemplate.update(
                "DELETE FROM routing.correspondent_route WHERE route_reference = ?",
                primary.routeReference()
        );
        assertThatThrownBy(() -> routingService.routePayment(routingPayment.paymentId()))
                .isInstanceOf(RouteConflictException.class);
    }

    // =========================================================================
    // 6. Invalid Payment States Rejected Without Side Effects
    // =========================================================================

    @Test
    void shouldRejectRoutePaymentForIneligiblePaymentStatesWithoutModifyingPaymentOrReservation() {
        BicCode destBic = uniqueBic("SG");
        AccountId sourceAccountId = createAccount("SGD", "ACTIVE", new BigDecimal("1000.0000"));

        // 1. Payment still in COMPLIANCE_HOLD (not yet approved for ROUTING)
        PaymentView complianceHoldPayment = paymentService.initiatePayment(new InitiatePaymentCommand(
                sourceAccountId,
                PaymentReference.of("PAY-RT-HOLD-" + shortId()),
                new BeneficiaryDetails("DBS Beneficiary", "SG1234567890", destBic),
                Money.of("100.0000", "SGD")
        ));
        assertThatThrownBy(() -> routingService.routePayment(complianceHoldPayment.paymentId()))
                .isInstanceOf(InvalidPaymentStateException.class);
        assertThat(paymentService.findPayment(complianceHoldPayment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.COMPLIANCE_HOLD);
        assertReservationStatus(complianceHoldPayment.reservationToken(), "ACTIVE", new BigDecimal("100.0000"), "SGD");

        // 2. Payment in CANCELLED state
        paymentService.cancelPayment(new CancelPaymentCommand(complianceHoldPayment.paymentId(), "Customer cancelled"));
        assertThatThrownBy(() -> routingService.routePayment(complianceHoldPayment.paymentId()))
                .isInstanceOf(InvalidPaymentStateException.class);

        // 3. Payment REJECTED for COMPLIANCE_REJECTED (not NO_ROUTE_AVAILABLE)
        PaymentView complianceRejectedPayment = paymentService.initiatePayment(new InitiatePaymentCommand(
                sourceAccountId,
                PaymentReference.of("PAY-RT-CREJ-" + shortId()),
                new BeneficiaryDetails("DBS Beneficiary", "SG1234567890", destBic),
                Money.of("100.0000", "SGD")
        ));
        paymentService.rejectPayment(new RejectPaymentCommand(
                complianceRejectedPayment.paymentId(),
                PaymentReasonCode.COMPLIANCE_REJECTED,
                "Sanctions match"
        ));
        assertThatThrownBy(() -> routingService.routePayment(complianceRejectedPayment.paymentId()))
                .isInstanceOf(InvalidPaymentStateException.class);

        // 4. Missing PaymentId & null arguments
        assertThatThrownBy(() -> routingService.routePayment(PaymentId.generate()))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> routingService.routePayment(null))
                .isInstanceOf(RoutingValidationException.class);
        assertThatThrownBy(() -> routingService.activateRoute("NON_EXISTENT_ROUTE"))
                .isInstanceOf(RouteNotFoundException.class);
        assertThatThrownBy(() -> routingService.deactivateRoute("NON_EXISTENT_ROUTE"))
                .isInstanceOf(RouteNotFoundException.class);
    }

    // =========================================================================
    // 7. Optimistic-Lock Rollback Safety on Both Commitment and No-Route Rejection Paths
    // =========================================================================

    @Test
    void shouldRollBackReservationReleaseAndPaymentTransitionWhenRoutePaymentEncountersOptimisticLockConflict() {
        AccountId sourceAccountId = createAccount("AED", "ACTIVE", new BigDecimal("1000.0000"));

        // Case A: No-route rejection path where Banking reservation is released before PaymentEntity flush fails
        BicCode unroutableBic = uniqueBic("AE");
        PaymentView unroutablePayment = createRoutingPayment(
                sourceAccountId,
                unroutableBic,
                Money.of("250.0000", "AED")
        );

        TransactionTemplate requiresNewTx = new TransactionTemplate(
                transactionManager,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        );

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            // 1. Prime current transaction's JPA context with ROUTING PaymentEntity
            paymentService.findPayment(unroutablePayment.paymentId());

            // 2. Concurrent transaction bumps PaymentEntity version in PostgreSQL
            requiresNewTx.executeWithoutResult(innerStatus ->
                    jdbcTemplate.update(
                            "UPDATE payment.payment SET version = version + 1 WHERE payment_id = ?",
                            unroutablePayment.paymentId().getValue()
                    )
            );

            // 3. routePayment calls paymentService.rejectPayment (which releases Banking reservation
            //    and updates stale PaymentEntity) -> fails at commit with ObjectOptimisticLockingFailureException!
            routingService.routePayment(unroutablePayment.paymentId());
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Verify both Payment and Banking reservation rolled back atomically:
        // Payment is still ROUTING and Banking reservation is still ACTIVE!
        assertThat(paymentService.findPayment(unroutablePayment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.ROUTING);
        assertReservationStatus(unroutablePayment.reservationToken(), "ACTIVE", new BigDecimal("250.0000"), "AED");
        assertAccountBalances(sourceAccountId, new BigDecimal("750.0000"), new BigDecimal("250.0000"));

        // Case B: Route-commitment path where PaymentEntity has concurrent version bump
        BicCode routableBic = uniqueBic("AE");
        routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_AED_" + shortId(),
                routableBic,
                SENDER_BIC,
                new Currency("AED"),
                "NOSTRO_AED_01",
                1
        ));
        PaymentView routablePayment = createRoutingPayment(
                sourceAccountId,
                routableBic,
                Money.of("150.0000", "AED")
        );

        assertThatThrownBy(() -> requiresNewTx.executeWithoutResult(status -> {
            paymentService.findPayment(routablePayment.paymentId());
            requiresNewTx.executeWithoutResult(innerStatus ->
                    jdbcTemplate.update(
                            "UPDATE payment.payment SET version = version + 1 WHERE payment_id = ?",
                            routablePayment.paymentId().getValue()
                    )
            );
            routingService.routePayment(routablePayment.paymentId());
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(paymentService.findPayment(routablePayment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.ROUTING);
        assertReservationStatus(routablePayment.reservationToken(), "ACTIVE", new BigDecimal("150.0000"), "AED");
    }

    // =========================================================================
    // 8. Concurrent Route Registration & Concurrent routePayment Races (8 Threads)
    // =========================================================================

    @Test
    void shouldHandleConcurrentRouteRegistrationAndConcurrentRoutePaymentSafely() throws Exception {
        int threads = 8;
        BicCode destBic = uniqueBic("IN");
        Currency inr = new Currency("INR");
        String sharedRouteRef = "ROUTE_CONC_INR_" + shortId();

        RegisterCorrespondentRouteCommand registerCmd = new RegisterCorrespondentRouteCommand(
                sharedRouteRef,
                destBic,
                SENDER_BIC,
                inr,
                "NOSTRO_INR_01",
                1
        );

        // Part A: 8 threads concurrently registering the exact same route
        CountDownLatch regReady = new CountDownLatch(threads);
        CountDownLatch regStart = new CountDownLatch(1);
        ExecutorService regPool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<RouteContext>> regTasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                regTasks.add(() -> {
                    regReady.countDown();
                    assertThat(regStart.await(10, TimeUnit.SECONDS)).isTrue();
                    return routingService.registerRoute(registerCmd);
                });
            }
            List<Future<RouteContext>> regFutures = new ArrayList<>(threads);
            for (Callable<RouteContext> task : regTasks) {
                regFutures.add(regPool.submit(task));
            }
            assertThat(regReady.await(10, TimeUnit.SECONDS)).isTrue();
            regStart.countDown();

            List<RouteContext> contexts = new ArrayList<>(threads);
            for (Future<RouteContext> future : regFutures) {
                contexts.add(future.get(20, TimeUnit.SECONDS));
            }
            Set<RouteId> distinctRouteIds = Set.copyOf(contexts.stream().map(RouteContext::routeId).toList());
            assertThat(distinctRouteIds).hasSize(1);
        } finally {
            regPool.shutdownNow();
        }

        // Part B: 8 threads concurrently routing the same ROUTING payment
        AccountId sourceAccountId = createAccount("INR", "ACTIVE", new BigDecimal("100000.0000"));
        PaymentView routingPayment = createRoutingPayment(
                sourceAccountId,
                destBic,
                Money.of("25000.0000", "INR")
        );

        CountDownLatch routeReady = new CountDownLatch(threads);
        CountDownLatch routeStart = new CountDownLatch(1);
        ExecutorService routePool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> routeTasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                routeTasks.add(() -> {
                    routeReady.countDown();
                    assertThat(routeStart.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        PaymentRoutingResult res = routingService.routePayment(routingPayment.paymentId());
                        return "SUCCESS:" + res.outcome() + ":" + res.payment().routeReference();
                    } catch (ObjectOptimisticLockingFailureException ex) {
                        return "OPTIMISTIC_LOCK";
                    }
                });
            }
            List<Future<String>> routeFutures = new ArrayList<>(threads);
            for (Callable<String> task : routeTasks) {
                routeFutures.add(routePool.submit(task));
            }
            assertThat(routeReady.await(10, TimeUnit.SECONDS)).isTrue();
            routeStart.countDown();

            List<String> outcomes = new ArrayList<>(threads);
            for (Future<String> future : routeFutures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(outcomes).anyMatch(o -> o.equals("SUCCESS:ROUTE_COMMITTED:" + sharedRouteRef));
            assertThat(outcomes).allMatch(
                    o -> o.equals("SUCCESS:ROUTE_COMMITTED:" + sharedRouteRef) || o.equals("OPTIMISTIC_LOCK")
            );

            PaymentView finalPayment = paymentService.findPayment(routingPayment.paymentId()).orElseThrow();
            assertThat(finalPayment.status()).isEqualTo(PaymentStatus.EXECUTING);
            assertThat(finalPayment.routeReference()).isEqualTo(sharedRouteRef);
            assertReservationStatus(routingPayment.reservationToken(), "ACTIVE", new BigDecimal("25000.0000"), "INR");
        } finally {
            routePool.shutdownNow();
        }
    }

    // =========================================================================
    // 9. BIC Canonicalization: 8-character and 11-character forms identify one institution
    // =========================================================================

    @Test
    void shouldTreatEightAndElevenCharacterBicsAsSameDestinationForRoutingConflictsAndReplay() {
        // Case 1: payment with 8-char BIC uses route registered with 11-char BIC
        BicCode eightA = new BicCode(uniqueBic("DE").getValue().substring(0, 8));
        BicCode elevenA = new BicCode(eightA.getValue() + "XXX");
        RouteContext routeA = routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_CANON_A_" + shortId(), elevenA, SENDER_BIC, new Currency("EUR"), "NOSTRO_CANON_A", 1
        ));
        assertThat(routeA.destinationBic()).isEqualTo(elevenA);

        AccountId accountA = createAccount("EUR", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView paymentA = createRoutingPayment(accountA, eightA, Money.of("100.0000", "EUR"));
        PaymentRoutingResult resultA = routingService.routePayment(paymentA.paymentId());
        assertThat(resultA.outcome()).isEqualTo(PaymentRoutingOutcome.ROUTE_COMMITTED);
        assertThat(resultA.selectedRoute()).contains(routeA);
        assertThat(paymentService.findPayment(paymentA.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.EXECUTING);
        assertReservationStatus(paymentA.reservationToken(), "ACTIVE", new BigDecimal("100.0000"), "EUR");

        // Case 4: replay after a different BIC form / after deactivation returns the original route
        routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_CANON_A_BACKUP_" + shortId(), eightA, SENDER_BIC, new Currency("EUR"), "NOSTRO_CANON_A2", 2
        ));
        routingService.deactivateRoute(routeA.routeReference());
        PaymentRoutingResult replayA = routingService.routePayment(paymentA.paymentId());
        assertThat(replayA.selectedRoute().orElseThrow().routeReference()).isEqualTo(routeA.routeReference());
        assertThat(routingService.selectRoute(new SelectRouteQuery(elevenA, new Currency("EUR"))).routeReference())
                .startsWith("ROUTE_CANON_A_BACKUP_");

        // Case 2: payment with 11-char BIC uses route registered with 8-char BIC
        BicCode eightB = new BicCode(uniqueBic("FR").getValue().substring(0, 8));
        BicCode elevenB = new BicCode(eightB.getValue() + "XXX");
        RouteContext routeB = routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_CANON_B_" + shortId(), eightB, SENDER_BIC, new Currency("EUR"), "NOSTRO_CANON_B", 1
        ));
        assertThat(routeB.destinationBic()).isEqualTo(elevenB);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT destination_bic FROM routing.correspondent_route WHERE route_reference = ?",
                String.class, routeB.routeReference())).isEqualTo(elevenB.getValue());

        AccountId accountB = createAccount("EUR", "ACTIVE", new BigDecimal("1000.0000"));
        PaymentView paymentB = createRoutingPayment(accountB, elevenB, Money.of("50.0000", "EUR"));
        PaymentRoutingResult resultB = routingService.routePayment(paymentB.paymentId());
        assertThat(resultB.outcome()).isEqualTo(PaymentRoutingOutcome.ROUTE_COMMITTED);
        assertThat(resultB.selectedRoute()).contains(routeB);

        // Lookup by either form returns the same routes
        assertThat(routingService.findRoutesForDestination(eightB, new Currency("EUR")))
                .containsExactly(routeB);
        assertThat(routingService.findRoutesForDestination(elevenB, new Currency("EUR")))
                .containsExactly(routeB);

        // Case 3: 8-char and 11-char forms cannot claim the same (destination, currency, priority) slot
        assertThatThrownBy(() -> routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                "ROUTE_CANON_B_DUP_" + shortId(), elevenB, SENDER_BIC, new Currency("EUR"), "NOSTRO_CANON_B_DUP", 1
        ))).isInstanceOf(RouteConflictException.class);
        assertThat(routingService.findRoutesForDestination(elevenB, new Currency("EUR"))).hasSize(1);

        // Idempotent replay using the other BIC form is accepted
        assertThat(routingService.registerRoute(new RegisterCorrespondentRouteCommand(
                routeB.routeReference(), elevenB, SENDER_BIC, new Currency("EUR"), "NOSTRO_CANON_B", 1
        ))).isEqualTo(routeB);

        // A different 11-character branch BIC is NOT equated
        BicCode branchBic = new BicCode(eightB.getValue() + "500");
        assertThatThrownBy(() -> routingService.selectRoute(new SelectRouteQuery(branchBic, new Currency("EUR"))))
                .isInstanceOf(NoValidRouteException.class);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private PaymentView createRoutingPayment(
            AccountId sourceAccountId,
            BicCode destinationBic,
            Money amount) {
        PaymentView initiated = paymentService.initiatePayment(new InitiatePaymentCommand(
                sourceAccountId,
                PaymentReference.of("PAY-RT-" + shortId()),
                new BeneficiaryDetails(
                        "Global Corp Beneficiary",
                        "DE89370400440532013000",
                        destinationBic
                ),
                amount
        ));
        return paymentService.approveCompliance(initiated.paymentId());
    }

    private AccountId createAccount(String currency, String status, BigDecimal availableBalance) {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = AccountId.generate();
        jdbcTemplate.update("INSERT INTO banking.customer (customer_id) VALUES (?)", customerId);
        jdbcTemplate.update(
                "INSERT INTO banking.account (account_id, customer_id, currency, status, available_balance, reserved_amount, version) "
                        + "VALUES (?, ?, ?, ?, ?, 0.0000, 0)",
                accountId.getValue(),
                customerId,
                currency,
                status,
                availableBalance
        );
        return accountId;
    }

    private void assertAccountBalances(
            AccountId accountId,
            BigDecimal expectedAvailable,
            BigDecimal expectedReserved) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT available_balance, reserved_amount FROM banking.account WHERE account_id = ?",
                accountId.getValue()
        );
        assertThat((BigDecimal) row.get("available_balance")).isEqualByComparingTo(expectedAvailable);
        assertThat((BigDecimal) row.get("reserved_amount")).isEqualByComparingTo(expectedReserved);
    }

    private void assertReservationStatus(
            ReservationToken token,
            String expectedStatus,
            BigDecimal expectedAmount,
            String expectedCurrency) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, amount, currency FROM banking.fund_reservation WHERE reservation_token = ?",
                token.getValue()
        );
        assertThat((String) row.get("status")).isEqualTo(expectedStatus);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(expectedAmount);
        assertThat((String) row.get("currency")).isEqualTo(expectedCurrency);
    }

    private static BicCode uniqueBic(String countryCode) {
        String raw = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        StringBuilder letters = new StringBuilder(4);
        for (int i = 0; i < raw.length() && letters.length() < 4; i++) {
            char c = raw.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                letters.append(c);
            } else {
                letters.append((char) ('A' + (c - '0')));
            }
        }
        String branch = raw.substring(4, 9);
        return new BicCode(letters + countryCode + branch);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
