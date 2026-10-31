package com.crosspaynet.security.portal.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class BrowserSecurityIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private AccountId seedCustomerAndAccount(UUID customerId, String currency, BigDecimal availableBalance) {
        AccountId accountId = AccountId.generate();
        jdbcTemplate.update(
                "INSERT INTO banking.customer (customer_id) VALUES (?) ON CONFLICT (customer_id) DO NOTHING",
                customerId
        );
        jdbcTemplate.update(
                "INSERT INTO banking.account (account_id, customer_id, currency, status, available_balance, reserved_amount, version) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?, 0.0000, 0)",
                accountId.getValue(),
                customerId,
                currency,
                availableBalance
        );
        return accountId;
    }

    @Test
    @DisplayName("GET /api/v1/portal/csrf returns token metadata and commits XSRF-TOKEN cookie")
    void csrfEndpointReturnsTokenAndCommitsCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/portal/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName", is("X-XSRF-TOKEN")))
                .andExpect(jsonPath("$.parameterName", is("_csrf")))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn();

        Cookie xsrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        String jsonToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        assertThat(jsonToken).isEqualTo(xsrfCookie.getValue());
    }

    @Test
    @DisplayName("POST /api/v1/portal/payments without CSRF token returns 403 Forbidden")
    void postWithoutCsrfTokenReturns403() throws Exception {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("1000.0000"));

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountId.getValue(),
                "PAY-NOCSRF-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal("100.0000"),
                "USD",
                "Recipient",
                "DE89370400440532013000",
                "DBEUMM21XXX"
        );

        mockMvc.perform(post("/api/v1/portal/payments")
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/portal/payments with valid CSRF cookie and X-XSRF-TOKEN header succeeds")
    void postWithValidCsrfSucceeds() throws Exception {
        // Step 1: Obtain the CSRF token and cookie via GET /api/v1/portal/csrf
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/portal/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        String token = xsrfCookie.getValue();

        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("1000.0000"));

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountId.getValue(),
                "PAY-CSRF-OK-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal("100.0000"),
                "USD",
                "Recipient",
                "DE89370400440532013000",
                "DBEUMM21XXX"
        );

        // Step 2: Submit the POST request with the cookie and header
        mockMvc.perform(post("/api/v1/portal/payments")
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/portal/payments with invalid CSRF token returns 403 Forbidden")
    void postWithInvalidCsrfTokenReturns403() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/portal/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();

        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("1000.0000"));

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountId.getValue(),
                "PAY-CSRF-BAD-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal("100.0000"),
                "USD",
                "Recipient",
                "DE89370400440532013000",
                "DBEUMM21XXX"
        );

        mockMvc.perform(post("/api/v1/portal/payments")
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", "invalid-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CORS preflight request from allowed origin http://localhost:3000 returns 200 OK")
    void corsPreflightAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/portal/payments")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("CORS preflight request from untrusted origin is rejected")
    void corsPreflightUntrustedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/portal/payments")
                        .header(HttpHeaders.ORIGIN, "http://untrusted-site.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
