package com.crosspaynet.infrastructure.demo;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.routing.RoutingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class LocalDemoDataInitializerTest {

    @Autowired
    private BankingService bankingService;

    @Autowired
    private RoutingService routingService;

    @Test
    @DisplayName("LocalDemoDataInitializer should create demo customer, USD account, and route idempotently")
    void shouldInitializeDemoDataIdempotently() {
        String testCustomerUser = "demo-user-" + UUID.randomUUID().toString().substring(0, 8);
        UUID customerId = UUID.nameUUIDFromBytes(testCustomerUser.getBytes(StandardCharsets.UTF_8));

        LocalDemoDataInitializer initializer = new LocalDemoDataInitializer(
                bankingService,
                routingService,
                testCustomerUser
        );

        // Run 1: initializes demo data
        initializer.initializeDemoData();

        List<CustomerAccountView> accountsAfterFirstRun = bankingService.findAccountsByCustomer(customerId);
        assertThat(accountsAfterFirstRun).hasSize(1);
        CustomerAccountView account = accountsAfterFirstRun.get(0);
        assertThat(account.currency().getCode()).isEqualTo("USD");
        assertThat(account.availableBalance().getAmount()).isEqualByComparingTo(new BigDecimal("10000.0000"));
        assertThat(account.totalBalance().getAmount()).isEqualByComparingTo(new BigDecimal("10000.0000"));
        assertThat(account.status()).isEqualTo("ACTIVE");

        // Reserve funds (simulating customer spending/reserving money on the account)
        bankingService.reserveFunds(
                account.accountId(),
                new com.crosspaynet.common.money.Money(
                        new BigDecimal("1500.0000"),
                        new com.crosspaynet.common.money.Currency("USD")
                ),
                com.crosspaynet.banking.contracts.PaymentReference.of("PAY-DEMO-SPEND-001")
        );

        // Run 2: second invocation must be completely idempotent (no duplicate account, no reset of post-payment balance)
        initializer.initializeDemoData();

        List<CustomerAccountView> accountsAfterSecondRun = bankingService.findAccountsByCustomer(customerId);
        assertThat(accountsAfterSecondRun).hasSize(1);
        assertThat(accountsAfterSecondRun.get(0).accountId()).isEqualTo(account.accountId());
        assertThat(accountsAfterSecondRun.get(0).availableBalance().getAmount()).isEqualByComparingTo(new BigDecimal("8500.0000"));
        assertThat(accountsAfterSecondRun.get(0).reservedAmount().getAmount()).isEqualByComparingTo(new BigDecimal("1500.0000"));
        assertThat(accountsAfterSecondRun.get(0).totalBalance().getAmount()).isEqualByComparingTo(new BigDecimal("10000.0000"));
    }
}
