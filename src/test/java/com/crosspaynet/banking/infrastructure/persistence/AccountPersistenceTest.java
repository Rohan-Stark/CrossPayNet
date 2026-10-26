package com.crosspaynet.banking.infrastructure.persistence;

import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.FundsReserved;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.AccountNotFoundException;
import com.crosspaynet.banking.contracts.CurrencyMismatchException;
import com.crosspaynet.banking.contracts.InsufficientFundsException;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.domain.AccountStatus;
import com.crosspaynet.banking.domain.CustomerId;
import com.crosspaynet.banking.domain.PersistedBankingStateException;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, BankingService.class})
@RecordApplicationEvents
class AccountPersistenceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private BankingService bankingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Test
    void testSaveAndLoadAccountWithReservation() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = new CustomerEntity(customerId);
        customerRepository.save(customer);

        UUID accountId = UUID.randomUUID();
        AccountEntity account = new AccountEntity(
                accountId,
                customerId,
                "USD",
                "ACTIVE",
                new BigDecimal("1000.0000"),
                new BigDecimal("200.0000")
        );

        FundReservationEntity reservation = new FundReservationEntity(
                UUID.randomUUID(),
                account,
                "REF-1",
                new BigDecimal("200.0000"),
                "USD",
                "ACTIVE"
        );
        account.addReservation(reservation);

        accountRepository.saveAndFlush(account);

        AccountEntity loaded = accountRepository.findById(accountId).orElse(null);
        assertNotNull(loaded);
        assertEquals(new BigDecimal("1000.0000"), loaded.getAvailableBalance());
        assertEquals(0L, loaded.getVersion());

        assertEquals(1, loaded.getReservations().size());
        assertEquals("REF-1", loaded.getReservations().get(0).getPaymentReference());
    }

    @Test
    void testUpdateExistingReservationInPlaceOnReleaseAndConsumeWithoutDuplicateKeyAndPreserveHistory() {
        UUID customerId = UUID.randomUUID();
        customerRepository.saveAndFlush(new CustomerEntity(customerId));

        UUID accountId = UUID.randomUUID();
        AccountEntity accountEntity = new AccountEntity(
                accountId,
                customerId,
                "USD",
                "ACTIVE",
                new BigDecimal("600.0000"),
                new BigDecimal("400.0000")
        );

        UUID token1 = UUID.randomUUID();
        UUID token2 = UUID.randomUUID();
        FundReservationEntity resEntity1 = new FundReservationEntity(
                token1,
                accountEntity,
                "PAY-INPLACE-1",
                new BigDecimal("250.0000"),
                "USD",
                "ACTIVE"
        );
        FundReservationEntity resEntity2 = new FundReservationEntity(
                token2,
                accountEntity,
                "PAY-INPLACE-2",
                new BigDecimal("150.0000"),
                "USD",
                "ACTIVE"
        );
        accountEntity.addReservation(resEntity1);
        accountEntity.addReservation(resEntity2);
        accountRepository.saveAndFlush(accountEntity);

        // Reconstruct domain, release token1, consume token2, and add a new reservation token3
        Account domain = AccountMapper.toDomain(accountEntity);
        domain.releaseReservation(new ReservationToken(token1));
        domain.consumeReservation(new ReservationToken(token2));
        ReservationToken token3 = domain.reserveFunds(
                Money.of("100.0000", "USD"),
                PaymentReference.of("PAY-INPLACE-3")
        );

        AccountMapper.updateEntity(domain, accountEntity);
        AccountEntity saved = accountRepository.saveAndFlush(accountEntity);

        // Verify managed instances for token1 and token2 were updated in place (same object references)
        Map<UUID, FundReservationEntity> byToken = saved.getReservations().stream()
                .collect(Collectors.toMap(FundReservationEntity::getReservationToken, r -> r));
        assertEquals(3, byToken.size());
        assertSame(resEntity1, byToken.get(token1));
        assertSame(resEntity2, byToken.get(token2));
        assertEquals("RELEASED", byToken.get(token1).getStatus());
        assertEquals("CONSUMED", byToken.get(token2).getStatus());
        assertEquals("ACTIVE", byToken.get(token3.getValue()).getStatus());

        assertEquals(new BigDecimal("750.0000"), saved.getAvailableBalance());
        assertEquals(new BigDecimal("100.0000"), saved.getReservedAmount());
        assertEquals(1L, saved.getVersion());
    }

    @Test
    void testUpdateEntityFailsSafelyWhenHistoricalReservationIsMissingFromDomainAggregate() {
        UUID customerId = UUID.randomUUID();
        customerRepository.saveAndFlush(new CustomerEntity(customerId));

        UUID accountId = UUID.randomUUID();
        AccountEntity accountEntity = new AccountEntity(
                accountId,
                customerId,
                "USD",
                "ACTIVE",
                new BigDecimal("1000.0000"),
                new BigDecimal("0.0000")
        );
        accountEntity.addReservation(new FundReservationEntity(
                UUID.randomUUID(),
                accountEntity,
                "PAY-HIST-1",
                new BigDecimal("100.0000"),
                "USD",
                "RELEASED"
        ));
        accountRepository.saveAndFlush(accountEntity);

        // Reconstruct an inconsistent Account aggregate missing the persisted RELEASED reservation
        Account inconsistentDomain = Account.restore(
                new AccountId(accountId),
                new CustomerId(customerId),
                new Currency("USD"),
                AccountStatus.ACTIVE,
                Money.of("1000.0000", "USD"),
                Money.of("0.0000", "USD"),
                List.of()
        );

        assertThrows(PersistedBankingStateException.class,
                () -> AccountMapper.updateEntity(inconsistentDomain, accountEntity));
        assertEquals(1, accountEntity.getReservations().size());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testReserveReleaseAndConsumeThroughBankingServiceEndToEndAndIdempotency() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID customerId = UUID.randomUUID();
        AccountId accountId = AccountId.generate();

        txTemplate.executeWithoutResult(status -> {
            customerRepository.save(new CustomerEntity(customerId));
            accountRepository.save(new AccountEntity(
                    accountId.getValue(),
                    customerId,
                    "USD",
                    "ACTIVE",
                    BigDecimal.ZERO.setScale(4),
                    BigDecimal.ZERO.setScale(4)
            ));
        });

        bankingService.fundAccount(accountId, Money.of("500.0000", "USD"));

        long eventsBefore = applicationEvents.stream(FundsReserved.class)
                .filter(e -> e.accountId().equals(accountId.getValue()))
                .count();

        PaymentReference ref1 = PaymentReference.of("PAY-E2E-1");
        ReservationToken token1 = bankingService.reserveFunds(accountId, Money.of("200.0000", "USD"), ref1);
        // Idempotent retry must return the same token and not emit a duplicate FundsReserved event
        ReservationToken token1Retry = bankingService.reserveFunds(accountId, Money.of("200.0000", "USD"), ref1);
        assertEquals(token1, token1Retry);

        long eventsAfterIdempotentRetry = applicationEvents.stream(FundsReserved.class)
                .filter(e -> e.accountId().equals(accountId.getValue()))
                .count();
        assertEquals(eventsBefore + 1, eventsAfterIdempotentRetry);

        bankingService.releaseReservation(accountId, token1);
        // Idempotent release retry
        bankingService.releaseReservation(accountId, token1);

        PaymentReference ref2 = PaymentReference.of("PAY-E2E-2");
        ReservationToken token2 = bankingService.reserveFunds(accountId, Money.of("300.0000", "USD"), ref2);
        bankingService.consumeReservation(accountId, token2);
        // Idempotent consume retry
        bankingService.consumeReservation(accountId, token2);

        txTemplate.executeWithoutResult(status -> {
            AccountEntity finalEntity = accountRepository.findById(accountId.getValue()).orElseThrow();
            assertEquals(new BigDecimal("200.0000"), finalEntity.getAvailableBalance());
            assertEquals(new BigDecimal("0.0000"), finalEntity.getReservedAmount());
            assertEquals(2, finalEntity.getReservations().size());

            Map<UUID, String> statuses = finalEntity.getReservations().stream()
                    .collect(Collectors.toMap(FundReservationEntity::getReservationToken, FundReservationEntity::getStatus));
            assertEquals("RELEASED", statuses.get(token1.getValue()));
            assertEquals("CONSUMED", statuses.get(token2.getValue()));
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testBusinessExceptionsInReserveFundsDoNotMarkCallerTransactionRollbackOnly() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID customerId = UUID.randomUUID();
        AccountId activeAccountId = AccountId.generate();
        AccountId frozenAccountId = AccountId.generate();

        txTemplate.executeWithoutResult(status -> {
            customerRepository.save(new CustomerEntity(customerId));
            accountRepository.save(new AccountEntity(
                    activeAccountId.getValue(),
                    customerId,
                    "USD",
                    "ACTIVE",
                    new BigDecimal("100.0000"),
                    new BigDecimal("0.0000")
            ));
            accountRepository.save(new AccountEntity(
                    frozenAccountId.getValue(),
                    customerId,
                    "USD",
                    "FROZEN",
                    new BigDecimal("500.0000"),
                    new BigDecimal("0.0000")
            ));
        });

        // Create one reservation on activeAccountId so we can also test InvalidReservationStateException
        bankingService.reserveFunds(
                activeAccountId,
                Money.of("40.0000", "USD"),
                PaymentReference.of("PAY-CONFLICT-REF")
        );

        UUID auditCustomerRowId = UUID.randomUUID();
        txTemplate.executeWithoutResult(status -> {
            // Simulate caller (e.g., Payment module) writing audit/rejection state in the shared transaction
            customerRepository.saveAndFlush(new CustomerEntity(auditCustomerRowId));

            // 1. AccountNotFoundException
            assertThrows(AccountNotFoundException.class, () ->
                    bankingService.reserveFunds(AccountId.generate(), Money.of("10.0000", "USD"), PaymentReference.of("P-MISSING")));
            assertFalse(status.isRollbackOnly());

            // 2. InsufficientFundsException
            assertThrows(InsufficientFundsException.class, () ->
                    bankingService.reserveFunds(activeAccountId, Money.of("500.0000", "USD"), PaymentReference.of("P-INSUFF")));
            assertFalse(status.isRollbackOnly());

            // 3. InvalidAccountStateException (FROZEN account)
            assertThrows(InvalidAccountStateException.class, () ->
                    bankingService.reserveFunds(frozenAccountId, Money.of("10.0000", "USD"), PaymentReference.of("P-FROZEN")));
            assertFalse(status.isRollbackOnly());

            // 4. CurrencyMismatchException
            assertThrows(CurrencyMismatchException.class, () ->
                    bankingService.reserveFunds(activeAccountId, Money.of("10.0000", "EUR"), PaymentReference.of("P-CURR")));
            assertFalse(status.isRollbackOnly());

            // 5. InvalidReservationStateException (conflicting amount for existing reference)
            assertThrows(InvalidReservationStateException.class, () ->
                    bankingService.reserveFunds(activeAccountId, Money.of("50.0000", "USD"), PaymentReference.of("PAY-CONFLICT-REF")));
            assertFalse(status.isRollbackOnly());
        });

        // Confirm the shared transaction committed the caller's audit record cleanly
        assertTrue(customerRepository.findById(auditCustomerRowId).isPresent());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testCorruptedPersistedStateAndUnexpectedFailuresMarkCallerTransactionRollbackOnly() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID customerId = UUID.randomUUID();
        AccountId corruptedAccountId = AccountId.generate();

        txTemplate.executeWithoutResult(status -> {
            customerRepository.saveAndFlush(new CustomerEntity(customerId));
            // Persist an account with corrupted reserved_amount (200.0000 reserved but 0 active reservation rows)
            jdbcTemplate.update(
                    "INSERT INTO banking.account (account_id, customer_id, currency, status, available_balance, reserved_amount, version) "
                            + "VALUES (?, ?, 'USD', 'ACTIVE', 500.0000, 200.0000, 0)",
                    corruptedAccountId.getValue(),
                    customerId
            );
        });

        // 1. Corrupted persisted state throws PersistedBankingStateException, marks global rollbackOnly,
        // and forces UnexpectedRollbackException if the caller catches it and attempts to commit.
        UUID rolledBackCustomer1 = UUID.randomUUID();
        assertThrows(UnexpectedRollbackException.class, () ->
                txTemplate.executeWithoutResult(status -> {
                    customerRepository.saveAndFlush(new CustomerEntity(rolledBackCustomer1));

                    assertThrows(PersistedBankingStateException.class, () ->
                            bankingService.reserveFunds(corruptedAccountId, Money.of("10.0000", "USD"), PaymentReference.of("P-CORRUPT")));
                })
        );
        assertFalse(customerRepository.findById(rolledBackCustomer1).isPresent());

        // 2. Programming/argument error (null argument) throws IllegalArgumentException, marks global rollbackOnly,
        // and forces UnexpectedRollbackException if the caller catches it and attempts to commit.
        UUID rolledBackCustomer2 = UUID.randomUUID();
        assertThrows(UnexpectedRollbackException.class, () ->
                txTemplate.executeWithoutResult(status -> {
                    customerRepository.saveAndFlush(new CustomerEntity(rolledBackCustomer2));

                    assertThrows(IllegalArgumentException.class, () ->
                            bankingService.reserveFunds(corruptedAccountId, null, PaymentReference.of("P-NULL")));
                })
        );
        assertFalse(customerRepository.findById(rolledBackCustomer2).isPresent());
    }
}
