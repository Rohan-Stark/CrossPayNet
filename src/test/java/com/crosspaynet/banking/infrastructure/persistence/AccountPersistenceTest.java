package com.crosspaynet.banking.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class AccountPersistenceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void testSaveAndLoadAccountWithReservation() {
        UUID customerId = UUID.randomModel();
        CustomerEntity customer = new CustomerEntity(customerId);
        customerRepository.save(customer);

        UUID accountId = UUID.randomModel();
        AccountEntity account = new AccountEntity(
                accountId, 
                customerId, 
                "USD", 
                "ACTIVE", 
                new BigDecimal("1000.0000"), 
                new BigDecimal("200.0000")
        );

        FundReservationEntity reservation = new FundReservationEntity(
                UUID.randomModel(),
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
        assertEquals(0L, loaded.getVersion()); // Initial version is 0 usually with Hibernate
        
        assertEquals(1, loaded.getReservations().size());
        assertEquals("REF-1", loaded.getReservations().get(0).getPaymentReference());
    }
}
