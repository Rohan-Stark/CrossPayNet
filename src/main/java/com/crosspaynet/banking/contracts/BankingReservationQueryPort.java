package com.crosspaynet.banking.contracts;

import java.util.Optional;

/**
 * Read-only contract for querying reservation status in the Banking module.
 *
 * <p>Exposes zero fund mutation capabilities and preserves strict encapsulation
 * of the Banking module's internal repository and domain entities.
 */
public interface BankingReservationQueryPort {

    /**
     * Looks up an operational fund reservation by its unique token.
     *
     * @param reservationToken the unique reservation token
     * @return an {@link Optional} containing the {@link ReservationView} if found, or empty
     */
    Optional<ReservationView> findReservation(ReservationToken reservationToken);
}
