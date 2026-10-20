package com.crosspaynet.banking.infrastructure.persistence;

import com.crosspaynet.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class AccountPersistenceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

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
        assertEquals(0L, loaded.getVersion()); // Initial version is 0 usually with Hibernate
        
        assertEquals(1, loaded.getReservations().size());
        assertEquals("REF-1", loaded.getReservations().get(0).getPaymentReference());
    }
}
