package com.crosspaynet.routing.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CorrespondentRouteEntity}.
 */
@Repository
public interface CorrespondentRouteRepository extends JpaRepository<CorrespondentRouteEntity, UUID> {

    Optional<CorrespondentRouteEntity> findByRouteReference(String routeReference);

    Optional<CorrespondentRouteEntity> findByDestinationBicAndCurrencyAndPriority(
            String destinationBic,
            String currency,
            int priority
    );

    List<CorrespondentRouteEntity> findByDestinationBicAndCurrencyAndStatusOrderByPriorityAscRouteReferenceAsc(
            String destinationBic,
            String currency,
            String status
    );

    List<CorrespondentRouteEntity> findByDestinationBicAndCurrencyOrderByPriorityAscRouteReferenceAsc(
            String destinationBic,
            String currency
    );

    /**
     * Conflict-safe insertion of a correspondent route claim using {@code ON CONFLICT DO NOTHING}
     * so duplicate or conflicting route registrations never abort the caller's PostgreSQL transaction.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO routing.correspondent_route (
                        route_id,
                        version,
                        route_reference,
                        destination_bic,
                        correspondent_bic,
                        currency,
                        clearing_account_reference,
                        priority,
                        status,
                        created_at,
                        updated_at
                    ) VALUES (
                        :routeId,
                        0,
                        :routeReference,
                        :destinationBic,
                        :correspondentBic,
                        :currency,
                        :clearingAccountReference,
                        :priority,
                        :status,
                        :createdAt,
                        :updatedAt
                    )
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertClaimOnConflictDoNothing(
            @Param("routeId") UUID routeId,
            @Param("routeReference") String routeReference,
            @Param("destinationBic") String destinationBic,
            @Param("correspondentBic") String correspondentBic,
            @Param("currency") String currency,
            @Param("clearingAccountReference") String clearingAccountReference,
            @Param("priority") int priority,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
