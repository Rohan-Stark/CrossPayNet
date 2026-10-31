package com.crosspaynet.security.portal.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.CustomerAccountView;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class CustomerAccountControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/portal/accounts";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private SecuredPaymentPortalService portalService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Nested
    @DisplayName("Authentication & Authorization Gates")
    class AuthorizationGates {

        @Test
        @DisplayName("Should reject unauthenticated access with 401 Unauthorized")
        void shouldRejectUnauthenticatedAccess() throws Exception {
            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(portalService);
        }

        @Test
        @DisplayName("Should reject non-customer roles with 403 Forbidden")
        void shouldRejectForbiddenRoles() throws Exception {
            for (String role : List.of("COMPLIANCE", "OPERATIONS", "ADMIN", "TELLER")) {
                mockMvc.perform(get(BASE_URL).with(user("user").roles(role)))
                        .andExpect(status().isForbidden());
            }

            verifyNoInteractions(portalService);
        }
    }

    @Nested
    @DisplayName("Customer Account Retrieval")
    class AccountRetrieval {

        @Test
        @DisplayName("Should return customer accounts when authenticated as ROLE_CUSTOMER")
        void shouldReturnCustomerAccounts() throws Exception {
            AccountId accountId = AccountId.generate();
            Currency usd = new Currency("USD");
            CustomerAccountView view = new CustomerAccountView(
                    accountId,
                    "****" + accountId.getValue().toString().substring(accountId.getValue().toString().length() - 4),
                    usd,
                    "ACTIVE",
                    new Money(new BigDecimal("10000.0000"), usd),
                    new Money(new BigDecimal("250.0000"), usd),
                    new Money(new BigDecimal("10250.0000"), usd)
            );

            when(portalService.findCustomerAccounts()).thenReturn(List.of(view));

            mockMvc.perform(get(BASE_URL).with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].accountId", is(accountId.getValue().toString())))
                    .andExpect(jsonPath("$[0].currency", is("USD")))
                    .andExpect(jsonPath("$[0].status", is("ACTIVE")))
                    .andExpect(jsonPath("$[0].availableBalance", is(10000.0000)))
                    .andExpect(jsonPath("$[0].reservedAmount", is(250.0000)))
                    .andExpect(jsonPath("$[0].totalBalance", is(10250.0000)))
                    .andExpect(jsonPath("$[0].maskedAccountNumber", is(view.maskedAccountNumber())));

            verify(portalService).findCustomerAccounts();
        }

        @Test
        @DisplayName("Should return empty list when customer has no accounts")
        void shouldReturnEmptyListWhenNoAccounts() throws Exception {
            when(portalService.findCustomerAccounts()).thenReturn(List.of());

            mockMvc.perform(get(BASE_URL).with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(portalService).findCustomerAccounts();
        }
    }
}
