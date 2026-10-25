package com.crosspaynet.banking;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.AccountNotFoundException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import com.crosspaynet.banking.infrastructure.persistence.AccountRepository;
import com.crosspaynet.banking.infrastructure.persistence.FundReservationEntity;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankingServiceTest {

    @Test
    void testReserveFundsPropagatesOptimisticLockingFailureWithoutRetry() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BankingService bankingService = new BankingService(accountRepository, eventPublisher);

        AccountId accountId = AccountId.generate();
        Money amount = Money.of("100", "USD");
        PaymentReference ref = PaymentReference.of("PAY-1");

        AccountEntity entityMock = mock(AccountEntity.class);
        when(entityMock.getAccountId()).thenReturn(accountId.getValue());
        when(entityMock.getCustomerId()).thenReturn(UUID.randomUUID());
        when(entityMock.getCurrency()).thenReturn("USD");
        when(entityMock.getStatus()).thenReturn("ACTIVE");
        when(entityMock.getAvailableBalance()).thenReturn(new BigDecimal("1000.0000"));
        when(entityMock.getReservedAmount()).thenReturn(new BigDecimal("0.0000"));

        when(accountRepository.findById(accountId.getValue())).thenReturn(Optional.of(entityMock));

        // Simulate save throwing Optimistic Locking Exception
        when(accountRepository.save(any(AccountEntity.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException("AccountEntity", "id"));

        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> bankingService.reserveFunds(accountId, amount, ref));

        // Save should only be attempted exactly once and no event published
        verify(accountRepository, times(1)).save(any(AccountEntity.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void testIdempotentReserveFundsDoesNotPublishDuplicateEventOrSaveAgain() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BankingService bankingService = new BankingService(accountRepository, eventPublisher);

        AccountId accountId = AccountId.generate();
        UUID existingTokenUuid = UUID.randomUUID();
        AccountEntity entity = new AccountEntity(
                accountId.getValue(),
                UUID.randomUUID(),
                "USD",
                "ACTIVE",
                new BigDecimal("700.0000"),
                new BigDecimal("300.0000")
        );
        entity.addReservation(new FundReservationEntity(
                existingTokenUuid,
                entity,
                "PAY-IDEMP-1",
                new BigDecimal("300.0000"),
                "USD",
                ReservationStatus.ACTIVE.name()
        ));

        when(accountRepository.findById(accountId.getValue())).thenReturn(Optional.of(entity));

        ReservationToken returnedToken = bankingService.reserveFunds(
                accountId,
                Money.of("300.0000", "USD"),
                PaymentReference.of("PAY-IDEMP-1")
        );

        assertEquals(new ReservationToken(existingTokenUuid), returnedToken);
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void testMissingAccountThrowsAccountNotFoundException() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BankingService bankingService = new BankingService(accountRepository, eventPublisher);

        AccountId missingId = AccountId.generate();
        when(accountRepository.findById(missingId.getValue())).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class,
                () -> bankingService.reserveFunds(missingId, Money.of("10", "USD"), PaymentReference.of("REF")));
        assertThrows(AccountNotFoundException.class,
                () -> bankingService.releaseReservation(missingId, ReservationToken.generate()));
        assertThrows(AccountNotFoundException.class,
                () -> bankingService.consumeReservation(missingId, ReservationToken.generate()));
        assertThrows(AccountNotFoundException.class,
                () -> bankingService.fundAccount(missingId, Money.of("10", "USD")));
    }
}
