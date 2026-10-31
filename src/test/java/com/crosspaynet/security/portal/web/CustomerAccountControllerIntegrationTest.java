package com.crosspaynet.security.portal.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class CustomerAccountControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/portal/accounts";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private BankingService bankingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("Should retrieve only the authenticated customer's accounts with exact balances")
    void shouldRetrieveAuthenticatedCustomerAccounts() throws Exception {
        String username = "cust-int-" + UUID.randomUUID().toString().substring(0, 8);
        UUID customerId = UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8));

        // Open account for this customer
        AccountId accountId = bankingService.openAccount(
                customerId,
                new Currency("USD"),
                new Money(new BigDecimal("5000.0000"), new Currency("USD"))
        );

        // Open account for a DIFFERENT customer
        UUID otherCustomerId = UUID.randomUUID();
        bankingService.openAccount(
                otherCustomerId,
                new Currency("EUR"),
                new Money(new BigDecimal("8000.0000"), new Currency("EUR"))
        );

        mockMvc.perform(get(BASE_URL).with(user(username).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accountId", is(accountId.getValue().toString())))
                .andExpect(jsonPath("$[0].currency", is("USD")))
                .andExpect(jsonPath("$[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$[0].availableBalance", is(5000.0000)))
                .andExpect(jsonPath("$[0].reservedAmount", is(0.0000)))
                .andExpect(jsonPath("$[0].totalBalance", is(5000.0000)))
                .andExpect(jsonPath("$[0].maskedAccountNumber", is("****" + accountId.getValue().toString().substring(accountId.getValue().toString().length() - 4))));
    }
}
