package com.crosspaynet.routing.infrastructure.persistence;

import com.crosspaynet.common.money.Currency;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.routing.contracts.RouteId;
import com.crosspaynet.routing.contracts.RouteStatus;
import com.crosspaynet.routing.domain.CorrespondentRoute;
import com.crosspaynet.routing.domain.PersistedRouteStateException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Translates between pure-domain {@link CorrespondentRoute} aggregates and
 * {@link CorrespondentRouteEntity} JPA entities.
 */
@Component
public class CorrespondentRouteMapper {

    public CorrespondentRouteEntity toEntity(CorrespondentRoute domain) {
        Objects.requireNonNull(domain, "CorrespondentRoute domain aggregate cannot be null.");
        return new CorrespondentRouteEntity(
                domain.getId().getValue(),
                domain.getRouteReference(),
                domain.getDestinationBic().getValue(),
                domain.getCorrespondentBic().getValue(),
                domain.getCurrency().getCode(),
                domain.getClearingAccountReference(),
                domain.getPriority(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public void updateEntity(CorrespondentRoute domain, CorrespondentRouteEntity entity) {
        Objects.requireNonNull(domain, "CorrespondentRoute domain aggregate cannot be null.");
        Objects.requireNonNull(entity, "CorrespondentRouteEntity cannot be null.");
        if (!domain.getId().getValue().equals(entity.getRouteId())) {
            throw new IllegalArgumentException(
                    "Cannot update CorrespondentRouteEntity '" + entity.getRouteId()
                            + "' from domain aggregate '" + domain.getId() + "'."
            );
        }
        entity.setStatus(domain.getStatus().name());
        entity.setUpdatedAt(domain.getUpdatedAt());
    }

    public CorrespondentRoute toDomain(CorrespondentRouteEntity entity) {
        if (entity == null) {
            throw new PersistedRouteStateException("Cannot restore CorrespondentRoute from null entity.");
        }
        try {
            RouteId routeId = entity.getRouteId() != null ? new RouteId(entity.getRouteId()) : null;
            BicCode destinationBic = entity.getDestinationBic() != null
                    ? new BicCode(entity.getDestinationBic())
                    : null;
            BicCode correspondentBic = entity.getCorrespondentBic() != null
                    ? new BicCode(entity.getCorrespondentBic())
                    : null;
            Currency currency = entity.getCurrency() != null
                    ? new Currency(entity.getCurrency())
                    : null;
            RouteStatus status = entity.getStatus() != null
                    ? RouteStatus.valueOf(entity.getStatus())
                    : null;

            return CorrespondentRoute.restore(
                    routeId,
                    entity.getRouteReference(),
                    destinationBic,
                    correspondentBic,
                    currency,
                    entity.getClearingAccountReference(),
                    entity.getPriority(),
                    status,
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        } catch (PersistedRouteStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PersistedRouteStateException(
                    "Corrupted persisted CorrespondentRoute state for routeId=" + entity.getRouteId()
                            + ": " + ex.getMessage(),
                    ex
            );
        }
    }
}
