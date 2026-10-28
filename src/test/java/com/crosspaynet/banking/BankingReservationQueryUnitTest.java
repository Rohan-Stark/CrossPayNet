package com.crosspaynet.banking;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.BankingReservationQueryPort;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.contracts.ReservationView;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import com.crosspaynet.banking.infrastructure.persistence.AccountRepository;
import com.crosspaynet.banking.infrastructure.persistence.FundReservationEntity;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BankingReservationQueryUnitTest {

    @Test
    void shouldFindReservationAndMapFieldsCorrectly() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BankingService bankingService = new BankingService(accountRepository, eventPublisher);

        UUID tokenUuid = UUID.randomUUID();
        UUID accountUuid = UUID.randomUUID();
        AccountEntity accountEntity = mock(AccountEntity.class);
        when(accountEntity.getAccountId()).thenReturn(accountUuid);

        FundReservationEntity entity = new FundReservationEntity(
                tokenUuid,
                accountEntity,
                "PAY-REF-1234",
                new BigDecimal("350.5000"),
                "USD",
                "ACTIVE"
        );

        when(accountRepository.findReservationByToken(tokenUuid)).thenReturn(Optional.of(entity));

        assertThat(bankingService).isInstanceOf(BankingReservationQueryPort.class);
        BankingReservationQueryPort queryPort = bankingService;
        ReservationToken token = new ReservationToken(tokenUuid);
        Optional<ReservationView> result = queryPort.findReservation(token);

        assertThat(result).isPresent();
        ReservationView view = result.get();
        assertThat(view.reservationToken()).isEqualTo(token);
        assertThat(view.accountId()).isEqualTo(new AccountId(accountUuid));
        assertThat(view.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(view.amount()).isEqualTo(new Money(new BigDecimal("350.5000"), new Currency("USD")));
        assertThat(view.paymentReference()).isEqualTo(PaymentReference.of("PAY-REF-1234"));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldReturnEmptyForUnknownReservationToken() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BankingService bankingService = new BankingService(accountRepository, eventPublisher);

        UUID tokenUuid = UUID.randomUUID();
        when(accountRepository.findReservationByToken(tokenUuid)).thenReturn(Optional.empty());

        Optional<ReservationView> result = bankingService.findReservation(new ReservationToken(tokenUuid));
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectNullReservationToken() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BankingService bankingService = new BankingService(accountRepository, eventPublisher);

        assertThatThrownBy(() -> bankingService.findReservation(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ReservationToken cannot be null");
    }
}
