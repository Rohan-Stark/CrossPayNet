package com.crosspaynet.banking;

import com.crosspaynet.banking.domain.AccountId;
import com.crosspaynet.banking.domain.PaymentReference;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import com.crosspaynet.banking.infrastructure.persistence.AccountRepository;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
        when(entityMock.getCustomerId()).thenReturn(UUID.randomModel());
        when(entityMock.getCurrency()).thenReturn("USD");
        when(entityMock.getStatus()).thenReturn("ACTIVE");
        when(entityMock.getAvailableBalance()).thenReturn(new java.math.BigDecimal("1000.0000"));
        when(entityMock.getReservedAmount()).thenReturn(new java.math.BigDecimal("0.0000"));

        when(accountRepository.findById(accountId.getValue())).thenReturn(Optional.of(entityMock));

        // Simulate save throwing Optimistic Locking Exception
        when(accountRepository.save(any(AccountEntity.class)))
            .thenThrow(new ObjectOptimisticLockingFailureException("AccountEntity", "id"));

        assertThrows(ObjectOptimisticLockingFailureException.class, 
            () -> bankingService.reserveFunds(accountId, amount, ref));
            
        // Save should only be attempted exactly once
        verify(accountRepository, times(1)).save(any(AccountEntity.class));
    }
}
