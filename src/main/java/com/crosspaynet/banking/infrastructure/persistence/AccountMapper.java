package com.crosspaynet.banking.infrastructure.persistence;

import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.domain.AccountId;
import com.crosspaynet.banking.domain.AccountStatus;
import com.crosspaynet.banking.domain.CustomerId;
import com.crosspaynet.banking.domain.FundReservation;
import com.crosspaynet.banking.domain.PaymentReference;
import com.crosspaynet.banking.domain.ReservationStatus;
import com.crosspaynet.banking.domain.ReservationToken;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;

import java.util.List;
import java.util.stream.Collectors;

public class AccountMapper {

    public static Account toDomain(AccountEntity entity) {
        if (entity == null) {
            return null;
        }

        Currency currency = new Currency(entity.getCurrency());
        
        List<FundReservation> reservations = entity.getReservations().stream()
                .map(resEntity -> {
                    FundReservation res = new FundReservation(
                            new ReservationToken(resEntity.getReservationToken()),
                            new PaymentReference(resEntity.getPaymentReference()),
                            new Money(resEntity.getAmount(), currency)
                    );
                    if (resEntity.getStatus().equals(ReservationStatus.RELEASED.name())) {
                        res.release();
                    } else if (resEntity.getStatus().equals(ReservationStatus.CONSUMED.name())) {
                        res.consume();
                    }
                    return res;
                })
                .collect(Collectors.toList());

        return Account.restore(
                new AccountId(entity.getAccountId()),
                new CustomerId(entity.getCustomerId()),
                currency,
                AccountStatus.valueOf(entity.getStatus()),
                new Money(entity.getAvailableBalance(), currency),
                new Money(entity.getReservedAmount(), currency),
                reservations
        );
    }

    public static void updateEntity(Account domain, AccountEntity entity) {
        if (domain == null || entity == null) {
            return;
        }

        entity.setStatus(domain.getStatus().name());
        entity.setAvailableBalance(domain.getAvailableBalance().getAmount());
        entity.setReservedAmount(domain.getReservedAmount().getAmount());

        // Update reservations
        // For simplicity, we clear and recreate, or we map by ID
        entity.getReservations().clear();
        for (FundReservation resDomain : domain.getReservations()) {
            FundReservationEntity resEntity = new FundReservationEntity(
                    resDomain.getToken().getValue(),
                    entity,
                    resDomain.getPaymentReference().getValue(),
                    resDomain.getAmount().getAmount(),
                    resDomain.getAmount().getCurrency().getCode(),
                    resDomain.getStatus().name()
            );
            entity.addReservation(resEntity);
        }
    }
}
