package com.crosspaynet.routing;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.routing.contracts.RouteId;
import com.crosspaynet.routing.contracts.RouteStatus;
import com.crosspaynet.routing.domain.CorrespondentRoute;
import com.crosspaynet.routing.domain.PersistedRouteStateException;
import com.crosspaynet.routing.infrastructure.persistence.CorrespondentRouteEntity;
import com.crosspaynet.routing.infrastructure.persistence.CorrespondentRouteMapper;
import com.crosspaynet.routing.infrastructure.persistence.CorrespondentRouteRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, CorrespondentRouteMapper.class})
class RoutingPersistenceIntegrationTest {

    private static final BicCode DEST_BIC = new BicCode("DEUTDEFFXXX");
    private static final BicCode CORR_BIC = new BicCode("CHASUS33XXX");
    private static final Currency EUR = new Currency("EUR");
    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-26T00:01:00Z");

    @Autowired
    private CorrespondentRouteRepository repository;

    @Autowired
    private CorrespondentRouteMapper mapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndRestoreActiveAndInactiveRoutesAndVerifyZeroCrossSchemaForeignKeys() {
        Integer fkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'routing'
                  AND table_name = 'correspondent_route'
                  AND constraint_type = 'FOREIGN KEY'
                """,
                Integer.class
        );
        assertThat(fkCount).isZero();

        CorrespondentRoute activeRoute = CorrespondentRoute.register(
                RouteId.generate(),
                "ROUTE_PERSIST_ACTIVE_" + shortId(),
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_DEUT_01",
                1,
                T0
        );
        assertRoundTrip(activeRoute);

        CorrespondentRoute inactiveRoute = CorrespondentRoute.register(
                RouteId.generate(),
                "ROUTE_PERSIST_INACTIVE_" + shortId(),
                DEST_BIC,
                CORR_BIC,
                EUR,
                "NOSTRO_EUR_DEUT_02",
                2,
                T0
        );
        inactiveRoute.deactivate(T1);
        assertRoundTrip(inactiveRoute);
    }

    @Test
    void shouldEnforceUniquenessCheckConstraintsAndUtf16SupplementaryUnicodeLengthBounds() {
        RawRow base = sampleRawRow();
        assertSqlState(base, "NO_ERROR");

        // Insert first row permanently in test transaction to test UNIQUE constraints
        CorrespondentRoute first = CorrespondentRoute.register(
                RouteId.generate(),
                "ROUTE_UNIQ_" + shortId(),
                new BicCode("BNPAFRPPXXX"),
                CORR_BIC,
                EUR,
                "NOSTRO_BNPA_EUR",
                5,
                T0
        );
        repository.saveAndFlush(mapper.toEntity(first));

        // 1. Duplicate route_reference -> SQLSTATE 23505
        assertSqlState(
                sampleRawRow().withRouteReference(first.getRouteReference()).withPriority(99),
                "23505"
        );

        // 2. Duplicate (destination_bic, currency, priority) -> SQLSTATE 23505
        assertSqlState(
                sampleRawRow()
                        .withDestinationBic("BNPAFRPPXXX")
                        .withCurrency("EUR")
                        .withPriority(5),
                "23505"
        );

        // 3. Priority out of bounds (0 or 1001) -> SQLSTATE 23514
        assertSqlState(sampleRawRow().withPriority(0), "23514");
        assertSqlState(sampleRawRow().withPriority(1001), "23514");

        // 4. Unsupported currency -> SQLSTATE 23514
        assertSqlState(sampleRawRow().withCurrency("CAD"), "23514");

        // 5. Invalid BIC -> SQLSTATE 23514
        assertSqlState(sampleRawRow().withDestinationBic("badbic"), "23514");
        assertSqlState(sampleRawRow().withCorrespondentBic("badbic"), "23514");

        // 6. Invalid status -> SQLSTATE 23514
        assertSqlState(sampleRawRow().withStatus("PENDING"), "23514");

        // 7. Invalid route_reference format (spaces or > 100 chars) -> SQLSTATE 23514
        assertSqlState(sampleRawRow().withRouteReference("BAD SPACE"), "23514");

        // 8. UTF-16 supplementary Unicode length alignment on clearing_account_reference (max 100 UTF-16 units):
        //    99 ASCII + 1 emoji (U+1F600 = 2 UTF-16 units) = 100 code points, 101 UTF-16 units -> rejected with 23514!
        String emoji = "\uD83D\uDE00";
        assertSqlState(
                sampleRawRow().withClearingAccount("C".repeat(99) + emoji),
                "23514"
        );

        // Positive UTF-16 boundary check: 98 ASCII + 1 emoji = 100 UTF-16 units succeeds and restores cleanly!
        CorrespondentRoute exactBoundary = CorrespondentRoute.register(
                RouteId.generate(),
                "ROUTE_UTF16_" + shortId(),
                new BicCode("UBSWSWZH80A"),
                CORR_BIC,
                new Currency("CHF"),
                "C".repeat(98) + emoji,
                1,
                T0
        );
        assertRoundTrip(exactBoundary);
    }

    @Test
    void shouldTranslateCorruptedEntityStateIntoPersistedRouteStateException() {
        CorrespondentRouteEntity corrupted = new CorrespondentRouteEntity(
                null,
                "ROUTE_CORRUPT_1",
                "DEUTDEFFXXX",
                "CHASUS33XXX",
                "EUR",
                "NOSTRO_EUR_1",
                1,
                "ACTIVE",
                T0,
                T1
        );
        assertThatThrownBy(() -> mapper.toDomain(corrupted))
                .isInstanceOf(PersistedRouteStateException.class);
    }

    private void assertRoundTrip(CorrespondentRoute domain) {
        CorrespondentRouteEntity saved = repository.saveAndFlush(mapper.toEntity(domain));
        entityManager.clear();

        CorrespondentRouteEntity loaded = repository.findById(saved.getRouteId()).orElseThrow();
        CorrespondentRoute restored = mapper.toDomain(loaded);
        assertThat(restored.toContext()).isEqualTo(domain.toContext());
    }

    private void assertSqlState(RawRow row, String expectedSqlState) {
        String actualState = jdbcTemplate.execute((ConnectionCallback<String>) con -> {
            Savepoint sp = con.setSavepoint();
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO routing.correspondent_route (
                        route_id, version, route_reference, destination_bic, correspondent_bic,
                        currency, clearing_account_reference, priority, status, created_at, updated_at
                    ) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, row.routeId);
                ps.setString(2, row.routeReference);
                ps.setString(3, row.destinationBic);
                ps.setString(4, row.correspondentBic);
                ps.setString(5, row.currency);
                ps.setString(6, row.clearingAccountReference);
                ps.setInt(7, row.priority);
                ps.setString(8, row.status);
                ps.setTimestamp(9, Timestamp.from(T0));
                ps.setTimestamp(10, Timestamp.from(T1));
                ps.executeUpdate();
                con.rollback(sp);
                return "NO_ERROR";
            } catch (SQLException ex) {
                con.rollback(sp);
                return ex.getSQLState();
            }
        });
        assertThat(actualState).isEqualTo(expectedSqlState);
    }

    private static RawRow sampleRawRow() {
        return new RawRow(
                UUID.randomUUID(),
                "ROUTE_RAW_" + shortId(),
                "DEUTDEFFXXX",
                "CHASUS33XXX",
                "EUR",
                "NOSTRO_EUR_01",
                100,
                "ACTIVE"
        );
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record RawRow(
            UUID routeId,
            String routeReference,
            String destinationBic,
            String correspondentBic,
            String currency,
            String clearingAccountReference,
            int priority,
            String status
    ) {
        RawRow withRouteReference(String newRef) {
            return new RawRow(routeId, newRef, destinationBic, correspondentBic, currency,
                    clearingAccountReference, priority, status);
        }

        RawRow withDestinationBic(String newDestBic) {
            return new RawRow(routeId, routeReference, newDestBic, correspondentBic, currency,
                    clearingAccountReference, priority, status);
        }

        RawRow withCorrespondentBic(String newCorrBic) {
            return new RawRow(routeId, routeReference, destinationBic, newCorrBic, currency,
                    clearingAccountReference, priority, status);
        }

        RawRow withCurrency(String newCurrency) {
            return new RawRow(routeId, routeReference, destinationBic, correspondentBic, newCurrency,
                    clearingAccountReference, priority, status);
        }

        RawRow withClearingAccount(String newClearing) {
            return new RawRow(routeId, routeReference, destinationBic, correspondentBic, currency,
                    newClearing, priority, status);
        }

        RawRow withPriority(int newPriority) {
            return new RawRow(routeId, routeReference, destinationBic, correspondentBic, currency,
                    clearingAccountReference, newPriority, status);
        }

        RawRow withStatus(String newStatus) {
            return new RawRow(routeId, routeReference, destinationBic, correspondentBic, currency,
                    clearingAccountReference, priority, newStatus);
        }
    }
}
