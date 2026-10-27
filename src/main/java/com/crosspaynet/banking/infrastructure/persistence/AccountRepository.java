package com.crosspaynet.banking.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    @Query("SELECT r FROM FundReservationEntity r WHERE r.reservationToken = :token")
    Optional<FundReservationEntity> findReservationByToken(@Param("token") UUID token);
}
