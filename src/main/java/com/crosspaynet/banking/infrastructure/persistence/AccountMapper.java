package com.crosspaynet.banking.infrastructure.persistence;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.domain.AccountStatus;
import com.crosspaynet.banking.domain.CustomerId;
import com.crosspaynet.banking.domain.FundReservation;
import com.crosspaynet.banking.domain.PersistedBankingStateException;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class AccountMapper {

    public static Account toDomain(AccountEntity entity) {
        if (entity == null) {
            return null;
        }

        try {
            Currency currency = new Currency(entity.getCurrency());
            AccountStatus status = AccountStatus.valueOf(entity.getStatus());

            List<FundReservation> reservations = new ArrayList<>();
            if (entity.getReservations() != null) {
                for (FundReservationEntity resEntity : entity.getReservations()) {
                    if (resEntity == null) {
                        throw new PersistedBankingStateException("Persisted reservation entity cannot be null.");
                    }
                    Currency resCurrency = new Currency(resEntity.getCurrency());
                    ReservationStatus resStatus = ReservationStatus.valueOf(resEntity.getStatus());
                    FundReservation res = FundReservation.restore(
                            new ReservationToken(resEntity.getReservationToken()),
                            new PaymentReference(resEntity.getPaymentReference()),
                            new Money(resEntity.getAmount(), resCurrency),
                            resStatus
                    );
                    reservations.add(res);
                }
            }

            return Account.restore(
                    new AccountId(entity.getAccountId()),
                    new CustomerId(entity.getCustomerId()),
                    currency,
                    status,
                    new Money(entity.getAvailableBalance(), currency),
                    new Money(entity.getReservedAmount(), currency),
                    reservations
            );
        } catch (PersistedBankingStateException ex) {
            throw ex;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new PersistedBankingStateException(
                    "Corrupted persisted Banking state for account '" + entity.getAccountId() + "': " + ex.getMessage(),
                    ex
            );
        }
    }

    public static void updateEntity(Account domain, AccountEntity entity) {
        if (domain == null || entity == null) {
            return;
        }

        if (!Objects.equals(domain.getAccountId().getValue(), entity.getAccountId())) {
            throw new PersistedBankingStateException(
                    "Cannot update AccountEntity '" + entity.getAccountId()
                            + "' from domain Account '" + domain.getAccountId() + "'."
            );
        }

        Map<UUID, FundReservationEntity> existingByToken = new LinkedHashMap<>();
        for (FundReservationEntity existingRes : entity.getReservations()) {
            if (existingRes == null || existingRes.getReservationToken() == null) {
                throw new PersistedBankingStateException("Persisted FundReservationEntity has a null token.");
            }
            if (existingByToken.put(existingRes.getReservationToken(), existingRes) != null) {
                throw new PersistedBankingStateException(
                        "Duplicate persisted FundReservationEntity token: " + existingRes.getReservationToken()
                );
            }
        }

        Map<UUID, FundReservation> domainByToken = new LinkedHashMap<>();
        for (FundReservation resDomain : domain.getReservations()) {
            UUID tokenValue = resDomain.getToken().getValue();
            if (domainByToken.put(tokenValue, resDomain) != null) {
                throw new PersistedBankingStateException(
                        "Duplicate domain FundReservation token: " + tokenValue
                );
            }
        }

        for (Map.Entry<UUID, FundReservationEntity> entry : existingByToken.entrySet()) {
            UUID existingToken = entry.getKey();
            FundReservationEntity existingRes = entry.getValue();
            FundReservation matchingDomain = domainByToken.get(existingToken);
            if (matchingDomain == null) {
                throw new PersistedBankingStateException(
                        "Persisted reservation token '" + existingToken
                                + "' is missing from reconstructed Account aggregate; refusing to delete reservation history."
                );
            }
            if (!Objects.equals(existingRes.getPaymentReference(), matchingDomain.getPaymentReference().getValue())
                    || !Objects.equals(existingRes.getCurrency(), matchingDomain.getAmount().getCurrency().getCode())
                    || existingRes.getAmount() == null
                    || existingRes.getAmount().compareTo(matchingDomain.getAmount().getAmount()) != 0) {
                throw new PersistedBankingStateException(
                        "Persisted reservation '" + existingToken
                                + "' immutable attributes do not match reconstructed domain reservation."
                );
            }
        }

        entity.setStatus(domain.getStatus().name());
        entity.setAvailableBalance(domain.getAvailableBalance().getAmount());
        entity.setReservedAmount(domain.getReservedAmount().getAmount());

        for (FundReservation resDomain : domain.getReservations()) {
            UUID tokenValue = resDomain.getToken().getValue();
            FundReservationEntity existingRes = existingByToken.get(tokenValue);
            if (existingRes != null) {
                String targetStatus = resDomain.getStatus().name();
                if (!targetStatus.equals(existingRes.getStatus())) {
                    existingRes.setStatus(targetStatus);
                }
            } else {
                FundReservationEntity newResEntity = new FundReservationEntity(
                        tokenValue,
                        entity,
                        resDomain.getPaymentReference().getValue(),
                        resDomain.getAmount().getAmount(),
                        resDomain.getAmount().getCurrency().getCode(),
                        resDomain.getStatus().name()
                );
                entity.addReservation(newResEntity);
            }
        }
    }
}
