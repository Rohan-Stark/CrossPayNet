package com.crosspaynet.routing;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.CommitPaymentExecutionCommand;
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
import com.crosspaynet.routing.contracts.RoutingBics;
import com.crosspaynet.routing.contracts.RoutingValidationException;
import com.crosspaynet.routing.contracts.SelectRouteQuery;
import com.crosspaynet.routing.domain.CorrespondentRoute;
import com.crosspaynet.routing.domain.PersistedRouteStateException;
import com.crosspaynet.routing.domain.RoutingLimits;
import com.crosspaynet.routing.infrastructure.persistence.CorrespondentRouteEntity;
import com.crosspaynet.routing.infrastructure.persistence.CorrespondentRouteMapper;
import com.crosspaynet.routing.infrastructure.persistence.CorrespondentRouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transactional orchestration and route-selection service for the Routing & Correspondent
 * Banking module (Phase 6).
 *
 * <p>Manages correspondent route registration and lifecycle ({@code ACTIVE <-> INACTIVE}),
 * deterministic route selection ({@code priority ASC, routeReference ASC}), and Payment routing
 * orchestration across {@code ROUTING -> EXECUTING} (Financial Commitment Point) and
 * {@code ROUTING -> REJECTED} ({@link PaymentReasonCode#NO_ROUTE_AVAILABLE} with atomic Banking
 * fund reservation release).
 */
@Service
public class RoutingService {

    private final CorrespondentRouteRepository routeRepository;
    private final CorrespondentRouteMapper routeMapper;
    private final PaymentService paymentService;

    public RoutingService(
            CorrespondentRouteRepository routeRepository,
            CorrespondentRouteMapper routeMapper,
            PaymentService paymentService) {
        this.routeRepository = Objects.requireNonNull(routeRepository, "routeRepository cannot be null.");
        this.routeMapper = Objects.requireNonNull(routeMapper, "routeMapper cannot be null.");
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService cannot be null.");
    }

    /**
     * Registers a correspondent banking route in {@link RouteStatus#ACTIVE} state with
     * conflict-safe idempotency.
     *
     * <p>If an {@link RouteStatus#ACTIVE} route already exists with the same
     * {@code routeReference} and identical attributes, returns the existing {@link RouteContext}.
     * If {@code routeReference} or {@code (destinationBic, currency, priority)} is already
     * claimed with different attributes (or the existing route is {@link RouteStatus#INACTIVE}),
     * throws {@link RouteConflictException} without marking the caller's transaction rollback-only.
     */
    @Transactional(
            propagation = Propagation.REQUIRED,
            noRollbackFor = RouteConflictException.class
    )
    public RouteContext registerRoute(RegisterCorrespondentRouteCommand command) {
        if (command == null) {
            throw new RoutingValidationException("RegisterCorrespondentRouteCommand cannot be null.");
        }

        Optional<CorrespondentRouteEntity> existingByRefOpt = routeRepository.findByRouteReference(
                command.routeReference()
        );
        if (existingByRefOpt.isPresent()) {
            CorrespondentRoute existing = routeMapper.toDomain(existingByRefOpt.get());
            return verifyExistingRouteRegistrationMatch(existing, command);
        }

        Optional<CorrespondentRouteEntity> existingBySlotOpt = routeRepository.findByDestinationBicAndCurrencyAndPriority(
                command.destinationBic().getValue(),
                command.currency().getCode(),
                command.priority()
        );
        if (existingBySlotOpt.isPresent()) {
            CorrespondentRoute existingSlot = routeMapper.toDomain(existingBySlotOpt.get());
            if (existingSlot.getRouteReference().equals(command.routeReference())) {
                return verifyExistingRouteRegistrationMatch(existingSlot, command);
            }
            throw new RouteConflictException(
                    "Routing priority slot (destinationBic='" + command.destinationBic().getValue()
                            + "', currency='" + command.currency().getCode()
                            + "', priority=" + command.priority()
                            + ") is already claimed by routeReference '"
                            + existingSlot.getRouteReference() + "'."
            );
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        CorrespondentRoute candidate = CorrespondentRoute.register(
                RouteId.generate(),
                command.routeReference(),
                command.destinationBic(),
                command.correspondentBic(),
                command.currency(),
                command.clearingAccountReference(),
                command.priority(),
                now
        );

        int inserted = routeRepository.insertClaimOnConflictDoNothing(
                candidate.getId().getValue(),
                candidate.getRouteReference(),
                candidate.getDestinationBic().getValue(),
                candidate.getCorrespondentBic().getValue(),
                candidate.getCurrency().getCode(),
                candidate.getClearingAccountReference(),
                candidate.getPriority(),
                candidate.getStatus().name(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );

        if (inserted == 0) {
            Optional<CorrespondentRouteEntity> raceWinnerByRef = routeRepository.findByRouteReference(
                    command.routeReference()
            );
            if (raceWinnerByRef.isPresent()) {
                CorrespondentRoute raceWinner = routeMapper.toDomain(raceWinnerByRef.get());
                return verifyExistingRouteRegistrationMatch(raceWinner, command);
            }

            Optional<CorrespondentRouteEntity> raceWinnerBySlot = routeRepository.findByDestinationBicAndCurrencyAndPriority(
                    command.destinationBic().getValue(),
                    command.currency().getCode(),
                    command.priority()
            );
            if (raceWinnerBySlot.isPresent()) {
                CorrespondentRoute slotWinner = routeMapper.toDomain(raceWinnerBySlot.get());
                if (slotWinner.getRouteReference().equals(command.routeReference())) {
                    return verifyExistingRouteRegistrationMatch(slotWinner, command);
                }
                throw new RouteConflictException(
                        "Routing priority slot (destinationBic='" + command.destinationBic().getValue()
                                + "', currency='" + command.currency().getCode()
                                + "', priority=" + command.priority()
                                + ") is already claimed by routeReference '"
                                + slotWinner.getRouteReference() + "'."
                );
            }

            throw new PersistedRouteStateException(
                    "CorrespondentRoute claim conflicted for routeReference '"
                            + command.routeReference() + "', but existing row could not be found."
            );
        }

        return candidate.toContext();
    }

    /**
     * Activates a correspondent route ({@code INACTIVE -> ACTIVE}).
     * Idempotent if the route is already {@link RouteStatus#ACTIVE}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RouteContext activateRoute(String routeReference) {
        String normalizedRef = CorrespondentRoute.validateRouteReference(routeReference);
        CorrespondentRouteEntity entity = loadEntityByReferenceOrThrow(normalizedRef);
        CorrespondentRoute route = routeMapper.toDomain(entity);

        if (route.getStatus() == RouteStatus.ACTIVE) {
            return route.toContext();
        }

        try {
            route.activate(nextTimestamp(route));
            routeMapper.updateEntity(route, entity);
            routeRepository.save(entity);
            return route.toContext();
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    /**
     * Deactivates a correspondent route ({@code ACTIVE -> INACTIVE}).
     * Idempotent if the route is already {@link RouteStatus#INACTIVE}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RouteContext deactivateRoute(String routeReference) {
        String normalizedRef = CorrespondentRoute.validateRouteReference(routeReference);
        CorrespondentRouteEntity entity = loadEntityByReferenceOrThrow(normalizedRef);
        CorrespondentRoute route = routeMapper.toDomain(entity);

        if (route.getStatus() == RouteStatus.INACTIVE) {
            return route.toContext();
        }

        try {
            route.deactivate(nextTimestamp(route));
            routeMapper.updateEntity(route, entity);
            routeRepository.save(entity);
            return route.toContext();
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    /**
     * Deterministically selects the highest-priority {@link RouteStatus#ACTIVE} correspondent route
     * matching {@code (destinationBic, currency)} ordered by {@code priority ASC, routeReference ASC}.
     *
     * @throws NoValidRouteException if no active route exists for the destination BIC and currency
     */
    @Transactional(readOnly = true)
    public RouteContext selectRoute(SelectRouteQuery query) {
        if (query == null) {
            throw new RoutingValidationException("SelectRouteQuery cannot be null.");
        }

        List<CorrespondentRouteEntity> activeCandidates = routeRepository
                .findByDestinationBicAndCurrencyAndStatusOrderByPriorityAscRouteReferenceAsc(
                        query.destinationBic().getValue(),
                        query.currency().getCode(),
                        RouteStatus.ACTIVE.name()
                );

        if (activeCandidates.isEmpty()) {
            throw new NoValidRouteException(query.destinationBic(), query.currency());
        }

        CorrespondentRoute winner = routeMapper.toDomain(activeCandidates.getFirst());
        return winner.toContext();
    }

    /**
     * Looks up a correspondent route by its unique {@code routeReference} (including both
     * {@link RouteStatus#ACTIVE} and {@link RouteStatus#INACTIVE} routes).
     */
    @Transactional(readOnly = true)
    public Optional<RouteContext> findRouteByReference(String routeReference) {
        String normalizedRef = CorrespondentRoute.validateRouteReference(routeReference);
        return routeRepository.findByRouteReference(normalizedRef)
                .map(routeMapper::toDomain)
                .map(CorrespondentRoute::toContext);
    }

    /**
     * Lists all correspondent routes (both {@link RouteStatus#ACTIVE} and {@link RouteStatus#INACTIVE})
     * configured for {@code (destinationBic, currency)}, ordered by {@code priority ASC, routeReference ASC}.
     */
    @Transactional(readOnly = true)
    public List<RouteContext> findRoutesForDestination(BicCode destinationBic, Currency currency) {
        if (destinationBic == null) {
            throw new RoutingValidationException("destinationBic cannot be null.");
        }
        if (currency == null) {
            throw new RoutingValidationException("currency cannot be null.");
        }
        if (!RoutingLimits.isSupportedCurrencyCode(currency.getCode())) {
            throw new RoutingValidationException("Unsupported routing currency: " + currency.getCode());
        }

        BicCode canonicalDestinationBic = RoutingBics.canonicalize(destinationBic);

        return routeRepository
                .findByDestinationBicAndCurrencyOrderByPriorityAscRouteReferenceAsc(
                        canonicalDestinationBic.getValue(),
                        currency.getCode()
                )
                .stream()
                .map(routeMapper::toDomain)
                .map(CorrespondentRoute::toContext)
                .toList();
    }

    /**
     * Evaluates and executes correspondent routing for a Payment:
     * <ul>
     *   <li>If the Payment is in {@link PaymentStatus#ROUTING} and an {@link RouteStatus#ACTIVE}
     *       route matches its beneficiary BIC and currency, commits the payment to execution
     *       ({@code ROUTING -> EXECUTING} at the Financial Commitment Point) and returns
     *       {@link PaymentRoutingOutcome#ROUTE_COMMITTED}.</li>
     *   <li>If the Payment is in {@link PaymentStatus#ROUTING} and no active route matches,
     *       rejects the payment ({@code ROUTING -> REJECTED} with
     *       {@link PaymentReasonCode#NO_ROUTE_AVAILABLE}, atomically releasing its Banking
     *       reservation) and returns {@link PaymentRoutingOutcome#REJECTED_NO_ROUTE}.</li>
     *   <li>If the Payment has already reached {@link PaymentStatus#EXECUTING},
     *       {@link PaymentStatus#PENDING_EXTERNAL}, {@link PaymentStatus#SETTLED}, or
     *       {@link PaymentStatus#FAILED} with a committed {@code routeReference}, resolves that
     *       historical route by reference (even if later deactivated) and returns
     *       {@link PaymentRoutingOutcome#ROUTE_COMMITTED} idempotently without re-selecting
     *       a new route.</li>
     *   <li>If the Payment is already {@link PaymentStatus#REJECTED} with
     *       {@link PaymentReasonCode#NO_ROUTE_AVAILABLE}, returns
     *       {@link PaymentRoutingOutcome#REJECTED_NO_ROUTE} idempotently.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentRoutingResult routePayment(PaymentId paymentId) {
        if (paymentId == null) {
            throw new RoutingValidationException("paymentId cannot be null.");
        }

        PaymentView payment = paymentService.findPayment(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (isPostCommitmentRoutedState(payment.status())) {
            return verifyAlreadyCommittedRouteReplay(payment);
        }

        if (payment.status() == PaymentStatus.REJECTED) {
            if (payment.statusReasonCode() == PaymentReasonCode.NO_ROUTE_AVAILABLE
                    && payment.reservationToken() != null) {
                return new PaymentRoutingResult(
                        paymentId,
                        PaymentRoutingOutcome.REJECTED_NO_ROUTE,
                        Optional.empty(),
                        payment
                );
            }
            throw new InvalidPaymentStateException(
                    "Cannot route Payment '" + paymentId + "' in state REJECTED with reasonCode: "
                            + payment.statusReasonCode()
            );
        }

        if (payment.status() != PaymentStatus.ROUTING) {
            throw new InvalidPaymentStateException(
                    "Cannot route Payment '" + paymentId + "' in state: " + payment.status()
                            + " (must be ROUTING)."
            );
        }

        BicCode destinationBic = RoutingBics.canonicalize(payment.instruction().beneficiary().beneficiaryBankBic());
        Currency currency = payment.instruction().amount().getCurrency();

        List<CorrespondentRouteEntity> activeCandidates = routeRepository
                .findByDestinationBicAndCurrencyAndStatusOrderByPriorityAscRouteReferenceAsc(
                        destinationBic.getValue(),
                        currency.getCode(),
                        RouteStatus.ACTIVE.name()
                );

        try {
            if (!activeCandidates.isEmpty()) {
                CorrespondentRoute selectedRoute = routeMapper.toDomain(activeCandidates.getFirst());
                RouteContext selectedContext = selectedRoute.toContext();

                PaymentView committedPayment = paymentService.commitToExecution(
                        new CommitPaymentExecutionCommand(paymentId, selectedContext.routeReference())
                );
                return new PaymentRoutingResult(
                        paymentId,
                        PaymentRoutingOutcome.ROUTE_COMMITTED,
                        Optional.of(selectedContext),
                        committedPayment
                );
            }

            String noRouteDetail = "No active correspondent route available for destination BIC "
                    + destinationBic.getValue() + " and currency " + currency.getCode();
            PaymentView rejectedPayment = paymentService.rejectPayment(
                    new RejectPaymentCommand(
                            paymentId,
                            PaymentReasonCode.NO_ROUTE_AVAILABLE,
                            noRouteDetail
                    )
            );
            return new PaymentRoutingResult(
                    paymentId,
                    PaymentRoutingOutcome.REJECTED_NO_ROUTE,
                    Optional.empty(),
                    rejectedPayment
            );
        } catch (RuntimeException | Error ex) {
            markCurrentTransactionRollbackOnly();
            throw ex;
        }
    }

    private PaymentRoutingResult verifyAlreadyCommittedRouteReplay(PaymentView payment) {
        String committedRouteRef = payment.routeReference();
        if (committedRouteRef == null || committedRouteRef.isBlank()) {
            markCurrentTransactionRollbackOnly();
            throw new RouteConflictException(
                    "Payment '" + payment.paymentId() + "' is in post-commitment state "
                            + payment.status() + " without a committed routeReference."
            );
        }

        CorrespondentRouteEntity routeEntity = routeRepository.findByRouteReference(committedRouteRef)
                .orElseThrow(() -> {
                    markCurrentTransactionRollbackOnly();
                    return new RouteConflictException(
                            "Committed routeReference '" + committedRouteRef + "' on Payment '"
                                    + payment.paymentId() + "' cannot be resolved in routing.correspondent_route."
                    );
                });

        CorrespondentRoute historicalRoute = routeMapper.toDomain(routeEntity);
        BicCode paymentDestinationBic = RoutingBics.canonicalize(
                payment.instruction().beneficiary().beneficiaryBankBic()
        );
        Currency paymentCurrency = payment.instruction().amount().getCurrency();

        if (!historicalRoute.getDestinationBic().equals(paymentDestinationBic)
                || !historicalRoute.getCurrency().equals(paymentCurrency)) {
            markCurrentTransactionRollbackOnly();
            throw new RouteConflictException(
                    "Committed routeReference '" + committedRouteRef + "' on Payment '"
                            + payment.paymentId() + "' has (destinationBic='"
                            + historicalRoute.getDestinationBic() + "', currency='"
                            + historicalRoute.getCurrency().getCode()
                            + "') which does not match payment instruction (destinationBic='"
                            + paymentDestinationBic + "', currency='" + paymentCurrency.getCode() + "')."
            );
        }

        return new PaymentRoutingResult(
                payment.paymentId(),
                PaymentRoutingOutcome.ROUTE_COMMITTED,
                Optional.of(historicalRoute.toContext()),
                payment
        );
    }

    private static boolean isPostCommitmentRoutedState(PaymentStatus status) {
        return status == PaymentStatus.EXECUTING
                || status == PaymentStatus.PENDING_EXTERNAL
                || status == PaymentStatus.SETTLED
                || status == PaymentStatus.FAILED;
    }

    private RouteContext verifyExistingRouteRegistrationMatch(
            CorrespondentRoute existing,
            RegisterCorrespondentRouteCommand command) {
        boolean attributesMatch = existing.getRouteReference().equals(command.routeReference())
                && existing.getDestinationBic().equals(command.destinationBic())
                && existing.getCorrespondentBic().equals(command.correspondentBic())
                && existing.getCurrency().equals(command.currency())
                && existing.getClearingAccountReference().equals(command.clearingAccountReference())
                && existing.getPriority() == command.priority()
                && existing.getStatus() == RouteStatus.ACTIVE;

        if (!attributesMatch) {
            throw new RouteConflictException(
                    "CorrespondentRoute '" + command.routeReference()
                            + "' already exists with conflicting attributes or status ("
                            + existing.getStatus() + ")."
            );
        }
        return existing.toContext();
    }

    private CorrespondentRouteEntity loadEntityByReferenceOrThrow(String routeReference) {
        return routeRepository.findByRouteReference(routeReference)
                .orElseThrow(() -> new RouteNotFoundException(routeReference));
    }

    private static Instant nextTimestamp(CorrespondentRoute route) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(route.getUpdatedAt()) ? route.getUpdatedAt() : now;
    }

    private static void markCurrentTransactionRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // No active Spring-managed transaction to mark
        }
    }
}
