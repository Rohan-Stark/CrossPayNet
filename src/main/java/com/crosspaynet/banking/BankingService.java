package com.crosspaynet.banking;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.AccountNotFoundException;
import com.crosspaynet.banking.contracts.CurrencyMismatchException;
import com.crosspaynet.banking.contracts.InsufficientFundsException;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import com.crosspaynet.banking.infrastructure.persistence.AccountMapper;
import com.crosspaynet.banking.infrastructure.persistence.AccountRepository;
import com.crosspaynet.common.money.Money;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class BankingService {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BankingService(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Reserves funds synchronously. Participates in the caller's transaction context.
     *
     * <p>Pre-write business rejections are configured with {@code noRollbackFor} so the caller (e.g., Payment module)
     * can catch business rejections, transition the payment to {@code REJECTED}, and commit the audit trail.
     * Corrupted persisted state, optimistic-locking conflicts, persistence errors, and unexpected runtime exceptions
     * always mark the transaction for rollback.
     */
    @Transactional(
            propagation = Propagation.REQUIRED,
            noRollbackFor = {
                    AccountNotFoundException.class,
                    InsufficientFundsException.class,
                    InvalidAccountStateException.class,
                    CurrencyMismatchException.class,
                    InvalidReservationStateException.class
            }
    )
    public ReservationToken reserveFunds(AccountId accountId, Money amount, PaymentReference paymentReference) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null.");
        }
        if (paymentReference == null) {
            throw new IllegalArgumentException("PaymentReference cannot be null.");
        }

        AccountEntity entity = accountRepository.findById(accountId.getValue())
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        Account domainAccount = AccountMapper.toDomain(entity);
        int priorReservationCount = domainAccount.getReservations().size();
        ReservationToken token = domainAccount.reserveFunds(amount, paymentReference);

        if (domainAccount.getReservations().size() == priorReservationCount) {
            return token;
        }

        AccountMapper.updateEntity(domainAccount, entity);
        accountRepository.save(entity);

        UUID eventId = UUID.randomUUID();
        eventPublisher.publishEvent(new FundsReserved(
                eventId,
                accountId.getValue(),
                token.getValue(),
                paymentReference.getValue(),
                amount,
                Instant.now(),
                paymentReference.getValue(),
                null
        ));

        return token;
    }

    /**
     * Releases an operational fund reservation ({@code ACTIVE -> RELEASED}) and restores available balance.
     * Idempotent if the reservation is already {@code RELEASED}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void releaseReservation(AccountId accountId, ReservationToken reservationToken) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null.");
        }
        if (reservationToken == null) {
            throw new IllegalArgumentException("ReservationToken cannot be null.");
        }

        AccountEntity entity = accountRepository.findById(accountId.getValue())
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        Account domainAccount = AccountMapper.toDomain(entity);
        domainAccount.releaseReservation(reservationToken);

        AccountMapper.updateEntity(domainAccount, entity);
        accountRepository.save(entity);
    }

    /**
     * Consumes an operational fund reservation ({@code ACTIVE -> CONSUMED}) and decrements reserved amount.
     * Idempotent if the reservation is already {@code CONSUMED}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void consumeReservation(AccountId accountId, ReservationToken reservationToken) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null.");
        }
        if (reservationToken == null) {
            throw new IllegalArgumentException("ReservationToken cannot be null.");
        }

        AccountEntity entity = accountRepository.findById(accountId.getValue())
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        Account domainAccount = AccountMapper.toDomain(entity);
        domainAccount.consumeReservation(reservationToken);

        AccountMapper.updateEntity(domainAccount, entity);
        accountRepository.save(entity);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void fundAccount(AccountId accountId, Money amount) {
        if (accountId == null) {
            throw new IllegalArgumentException("AccountId cannot be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null.");
        }

        AccountEntity entity = accountRepository.findById(accountId.getValue())
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        Account domainAccount = AccountMapper.toDomain(entity);
        domainAccount.fundSimulator(amount);

        AccountMapper.updateEntity(domainAccount, entity);
        accountRepository.save(entity);
    }
}
