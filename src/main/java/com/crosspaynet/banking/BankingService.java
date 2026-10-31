package com.crosspaynet.banking;

import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.AccountNotFoundException;
import com.crosspaynet.banking.contracts.CurrencyMismatchException;
import com.crosspaynet.banking.contracts.InsufficientFundsException;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.contracts.ReservationView;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import com.crosspaynet.banking.infrastructure.persistence.AccountMapper;
import com.crosspaynet.banking.infrastructure.persistence.AccountRepository;
import com.crosspaynet.banking.infrastructure.persistence.CustomerEntity;
import com.crosspaynet.banking.infrastructure.persistence.CustomerRepository;
import com.crosspaynet.common.money.Money;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class BankingService implements com.crosspaynet.banking.contracts.BankingReservationQueryPort {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BankingService(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher) {
        this(accountRepository, null, eventPublisher);
    }

    @Autowired
    public BankingService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            ApplicationEventPublisher eventPublisher) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository cannot be null.");
        this.customerRepository = customerRepository;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null.");
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

    @PreAuthorize("hasRole('ADMIN')")
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

    @Transactional(readOnly = true)
    public boolean isAccountOwnedBy(AccountId accountId, UUID customerId) {
        if (accountId == null || customerId == null) {
            return false;
        }
        return accountRepository.findById(accountId.getValue())
                .map(entity -> entity.getCustomerId().equals(customerId))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<ReservationView> findReservation(ReservationToken reservationToken) {
        if (reservationToken == null) {
            throw new IllegalArgumentException("ReservationToken cannot be null.");
        }
        return accountRepository.findReservationByToken(reservationToken.getValue())
                .map(entity -> new ReservationView(
                        new ReservationToken(entity.getReservationToken()),
                        new AccountId(entity.getAccount().getAccountId()),
                        ReservationStatus.valueOf(entity.getStatus()),
                        new Money(entity.getAmount(), new Currency(entity.getCurrency())),
                        PaymentReference.of(entity.getPaymentReference())
                ));
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findAccountOwner(AccountId accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return accountRepository.findById(accountId.getValue())
                .map(AccountEntity::getCustomerId);
    }

    /**
     * Finds all accounts owned by an individual customer.
     */
    @Transactional(readOnly = true)
    public List<CustomerAccountView> findAccountsByCustomer(UUID customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("CustomerId cannot be null.");
        }
        return accountRepository.findByCustomerIdOrderByAccountIdAsc(customerId).stream()
                .map(entity -> {
                    Currency currency = new Currency(entity.getCurrency());
                    Money available = new Money(entity.getAvailableBalance(), currency);
                    Money reserved = new Money(entity.getReservedAmount(), currency);
                    Money total = available.add(reserved);
                    String idStr = entity.getAccountId().toString();
                    String masked = idStr.length() > 4 ? "****" + idStr.substring(idStr.length() - 4) : "****";
                    return new CustomerAccountView(
                            new AccountId(entity.getAccountId()),
                            masked,
                            currency,
                            entity.getStatus(),
                            available,
                            reserved,
                            total
                    );
                })
                .toList();
    }

    /**
     * Creates a customer record if it does not already exist.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void createCustomerIfNotExists(UUID customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("CustomerId cannot be null.");
        }
        Objects.requireNonNull(customerRepository, "customerRepository must be configured.");
        if (!customerRepository.existsById(customerId)) {
            customerRepository.save(new CustomerEntity(customerId));
        }
    }

    /**
     * Opens an account for an existing or newly created customer.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AccountId openAccount(UUID customerId, Currency currency, Money initialBalance) {
        if (customerId == null) {
            throw new IllegalArgumentException("CustomerId cannot be null.");
        }
        if (currency == null) {
            throw new IllegalArgumentException("Currency cannot be null.");
        }
        if (initialBalance == null) {
            throw new IllegalArgumentException("InitialBalance cannot be null.");
        }
        createCustomerIfNotExists(customerId);
        AccountId accountId = AccountId.generate();
        AccountEntity entity = new AccountEntity(
                accountId.getValue(),
                customerId,
                currency.getCode(),
                "ACTIVE",
                initialBalance.getAmount(),
                BigDecimal.ZERO.setScale(4)
        );
        accountRepository.save(entity);
        return accountId;
    }
}
