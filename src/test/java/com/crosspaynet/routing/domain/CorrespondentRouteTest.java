package com.crosspaynet.routing.domain;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.routing.contracts.PaymentRoutingOutcome;
import com.crosspaynet.routing.contracts.PaymentRoutingResult;
import com.crosspaynet.routing.contracts.RegisterCorrespondentRouteCommand;
import com.crosspaynet.routing.contracts.RouteContext;
import com.crosspaynet.routing.contracts.RouteId;
import com.crosspaynet.routing.contracts.RouteStatus;
import com.crosspaynet.routing.contracts.RoutingBics;
import com.crosspaynet.routing.contracts.RoutingValidationException;
import com.crosspaynet.routing.contracts.SelectRouteQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrespondentRouteTest {

    private static final BicCode DEST_BIC = new BicCode("DEUTDEFFXXX");
    private static final BicCode CORR_BIC = new BicCode("CHASUS33XXX");
    private static final Currency EUR = new Currency("EUR");
    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-26T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-10-26T00:02:00Z");

    @Test
    void shouldRegisterActivateDeactivateAndEvaluateRouteEligibility() {
        RouteId routeId = RouteId.generate();
        CorrespondentRoute route = CorrespondentRoute.register(
                routeId,
                "  ROUTE_EUR_DEUT_01  ",
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_CHASUS33",
                1,
                T0
        );

        assertThat(route.getId()).isEqualTo(routeId);
        assertThat(route.getRouteReference()).isEqualTo("ROUTE_EUR_DEUT_01");
        assertThat(route.getDestinationBic()).isEqualTo(DEST_BIC);
        assertThat(route.getCorrespondentBic()).isEqualTo(CORR_BIC);
        assertThat(route.getCurrency()).isEqualTo(EUR);
        assertThat(route.getClearingAccountReference()).isEqualTo("NOSTRO_EUR_CHASUS33");
        assertThat(route.getPriority()).isEqualTo(1);
        assertThat(route.getStatus()).isEqualTo(RouteStatus.ACTIVE);
        assertThat(route.getCreatedAt()).isEqualTo(T0);
        assertThat(route.getUpdatedAt()).isEqualTo(T0);

        assertThat(route.isEligibleFor(DEST_BIC, EUR)).isTrue();
        assertThat(route.isEligibleFor(DEST_BIC, new Currency("USD"))).isFalse();
        assertThat(route.isEligibleFor(CORR_BIC, EUR)).isFalse();

        // Idempotent activate when already ACTIVE
        route.activate(T0);
        assertThat(route.getStatus()).isEqualTo(RouteStatus.ACTIVE);

        // Deactivate -> INACTIVE
        route.deactivate(T1);
        assertThat(route.getStatus()).isEqualTo(RouteStatus.INACTIVE);
        assertThat(route.getUpdatedAt()).isEqualTo(T1);
        assertThat(route.isEligibleFor(DEST_BIC, EUR)).isFalse();

        // Idempotent deactivate when already INACTIVE
        route.deactivate(T1);
        assertThat(route.getStatus()).isEqualTo(RouteStatus.INACTIVE);

        // Reactivate -> ACTIVE
        route.activate(T2);
        assertThat(route.getStatus()).isEqualTo(RouteStatus.ACTIVE);
        assertThat(route.getUpdatedAt()).isEqualTo(T2);
        assertThat(route.isEligibleFor(DEST_BIC, EUR)).isTrue();

        // Timestamp regression rejected
        assertThatThrownBy(() -> route.deactivate(T1))
                .isInstanceOf(RoutingValidationException.class);
        assertThatThrownBy(() -> route.activate(null))
                .isInstanceOf(RoutingValidationException.class);
    }

    @Test
    void shouldRestorePersistedRouteAndRejectCorruptedState() {
        CorrespondentRoute original = CorrespondentRoute.register(
                RouteId.generate(),
                "ROUTE_EUR_DEUT_01",
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_CHASUS33",
                10,
                T0
        );
        original.deactivate(T1);

        CorrespondentRoute restored = CorrespondentRoute.restore(
                original.getId(),
                original.getRouteReference(),
                original.getDestinationBic(),
                original.getCorrespondentBic(),
                original.getCurrency(),
                original.getClearingAccountReference(),
                original.getPriority(),
                original.getStatus(),
                original.getCreatedAt(),
                original.getUpdatedAt()
        );
        assertThat(restored.toContext()).isEqualTo(original.toContext());

        // Corrupted routeReference (untrimmed or invalid chars) rejected
        assertThatThrownBy(() -> CorrespondentRoute.restore(
                original.getId(),
                " UNTRIMMED ",
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_CHASUS33",
                10,
                RouteStatus.ACTIVE,
                T0,
                T1
        )).isInstanceOf(PersistedRouteStateException.class);

        // Corrupted currency rejected
        assertThatThrownBy(() -> CorrespondentRoute.restore(
                original.getId(),
                "ROUTE_EUR_DEUT_01",
                DEST_BIC,
                CORR_BIC,
                new Currency("CAD"),
                "NOSTRO_EUR_CHASUS33",
                10,
                RouteStatus.ACTIVE,
                T0,
                T1
        )).isInstanceOf(PersistedRouteStateException.class);

        // Corrupted priority out of bounds rejected
        assertThatThrownBy(() -> CorrespondentRoute.restore(
                original.getId(),
                "ROUTE_EUR_DEUT_01",
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_CHASUS33",
                1001,
                RouteStatus.ACTIVE,
                T0,
                T1
        )).isInstanceOf(PersistedRouteStateException.class);

        // Corrupted timestamp order (updatedAt < createdAt) rejected
        assertThatThrownBy(() -> CorrespondentRoute.restore(
                original.getId(),
                "ROUTE_EUR_DEUT_01",
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_CHASUS33",
                10,
                RouteStatus.ACTIVE,
                T1,
                T0
        )).isInstanceOf(PersistedRouteStateException.class);
    }

    @Test
    void shouldCanonicalizeEightCharacterBicsAndPreserveElevenCharacterBranchBics() {
        BicCode eight = new BicCode("DEUTDEFF");
        BicCode eleven = new BicCode("DEUTDEFFXXX");
        BicCode branch = new BicCode("DEUTDEFF500");

        assertThat(RoutingBics.canonicalize(eight)).isEqualTo(eleven);
        assertThat(RoutingBics.canonicalize(eleven)).isEqualTo(eleven);
        assertThat(RoutingBics.canonicalize(branch)).isEqualTo(branch).isNotEqualTo(eleven);

        CorrespondentRoute route = CorrespondentRoute.register(
                RouteId.generate(), "ROUTE_CANON_1", eight, new BicCode("CHASUS33"), EUR,
                "NOSTRO_1", 1, T0
        );
        assertThat(route.getDestinationBic()).isEqualTo(eleven);
        assertThat(route.getCorrespondentBic()).isEqualTo(new BicCode("CHASUS33XXX"));
        assertThat(route.isEligibleFor(RoutingBics.canonicalize(eight), EUR)).isTrue();
        assertThat(route.isEligibleFor(branch, EUR)).isFalse();
        assertThat(route.toContext().destinationBic()).isEqualTo(eleven);

        assertThat(new SelectRouteQuery(eight, EUR).destinationBic()).isEqualTo(eleven);
        assertThat(new RegisterCorrespondentRouteCommand(
                "ROUTE_CANON_2", eight, eight, EUR, "NOSTRO_1", 1
        ).destinationBic()).isEqualTo(eleven);
    }

    @Test
    void shouldRejectClearingAccountsWithLeadingOrTrailingWhitespaceMatchingLedgerRule() {
        String[] invalid = {
                "NOSTRO_1\u00A0",
                "\u00A0NOSTRO_1",
                "NOSTRO_1\u2003",
                "NOSTRO_1 ",
                " NOSTRO_1"
        };
        for (String value : invalid) {
            assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                    "ROUTE_WS", DEST_BIC, CORR_BIC, EUR, value, 1
            )).isInstanceOf(RoutingValidationException.class);
            assertThatThrownBy(() -> CorrespondentRoute.register(
                    RouteId.generate(), "ROUTE_WS", DEST_BIC, CORR_BIC, EUR, value, 1, T0
            )).isInstanceOf(RoutingValidationException.class);
            assertThatThrownBy(() -> CorrespondentRoute.restore(
                    RouteId.generate(), "ROUTE_WS", DEST_BIC, CORR_BIC, EUR, value, 1,
                    RouteStatus.ACTIVE, T0, T0
            )).isInstanceOf(PersistedRouteStateException.class);
        }
        // Internal spacing remains allowed
        assertThat(new RegisterCorrespondentRouteCommand(
                "ROUTE_WS", DEST_BIC, CORR_BIC, EUR, "NOSTRO 1", 1
        ).clearingAccountReference()).isEqualTo("NOSTRO 1");
    }

    @Test
    void shouldValidateCommandsQueriesAndContractBoundaries() {
        assertThatThrownBy(() -> new RouteId(null))
                .isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                " ", DEST_BIC, CORR_BIC, EUR, "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "BAD SPACE", DEST_BIC, CORR_BIC, EUR, "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "R".repeat(101), DEST_BIC, CORR_BIC, EUR, "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE\u0007", DEST_BIC, CORR_BIC, EUR, "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", null, CORR_BIC, EUR, "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", DEST_BIC, null, EUR, "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", DEST_BIC, CORR_BIC, new Currency("CAD"), "NOSTRO_1", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", DEST_BIC, CORR_BIC, EUR, "NOSTRO\u0007", 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", DEST_BIC, CORR_BIC, EUR, "C".repeat(101), 1
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", DEST_BIC, CORR_BIC, EUR, "NOSTRO_1", 0
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new RegisterCorrespondentRouteCommand(
                "ROUTE_1", DEST_BIC, CORR_BIC, EUR, "NOSTRO_1", 1001
        )).isInstanceOf(RoutingValidationException.class);

        assertThatThrownBy(() -> new SelectRouteQuery(null, EUR))
                .isInstanceOf(RoutingValidationException.class);
        assertThatThrownBy(() -> new SelectRouteQuery(DEST_BIC, new Currency("CAD")))
                .isInstanceOf(RoutingValidationException.class);

        RouteContext validContext = new RouteContext(
                RouteId.generate(),
                "ROUTE_1",
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_1",
                1,
                RouteStatus.ACTIVE,
                T0,
                T0
        );
        assertThatThrownBy(() -> new PaymentRoutingResult(
                PaymentId.generate(),
                PaymentRoutingOutcome.ROUTE_COMMITTED,
                Optional.empty(),
                null
        )).isInstanceOf(RoutingValidationException.class);
        assertThat(validContext.routeReference()).isEqualTo("ROUTE_1");
    }
}
