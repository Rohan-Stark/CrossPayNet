package com.crosspaynet.security.portal.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class PaymentPortalControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/portal/payments";

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

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Scenario 1: Happy path end-to-end payment initiation and retrieval")
    void shouldInitiatePaymentAndRetrieveSuccessfully() throws Exception {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("5000.0000"));
        String reference = "PAY-INT-" + shortId();

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountId.getValue(),
                reference,
                new BigDecimal("500.0000"),
                "USD",
                "British Telecom PLC",
                "GB29NWBK60161331926819",
                "NWBKGB2LXXX"
        );

        MvcResult postResult = mockMvc.perform(post(BASE_URL)
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.sourceAccountId", is(accountId.getValue().toString())))
                .andExpect(jsonPath("$.paymentReference", is(reference)))
                .andExpect(jsonPath("$.beneficiaryName", is("British Telecom PLC")))
                .andExpect(jsonPath("$.beneficiaryAccountNumber", is("****6819")))
                .andExpect(jsonPath("$.beneficiaryBankBic", is("NWBKGB2LXXX")))
                .andExpect(jsonPath("$.amount", is(500.0000)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.status", is("COMPLIANCE_HOLD")))
                .andExpect(jsonPath("$.reservationToken").doesNotExist())
                .andExpect(jsonPath("$.ledgerTransactionId").doesNotExist())
                .andExpect(jsonPath("$.routeReference").doesNotExist())
                .andExpect(jsonPath("$.externalDispatchReference").doesNotExist())
                .andReturn();

        JsonNode postJson = objectMapper.readTree(postResult.getResponse().getContentAsString());
        String paymentId = postJson.get("paymentId").asText();
        String locationHeader = postResult.getResponse().getHeader("Location");
        assertThat(locationHeader).isEqualTo(BASE_URL + "/" + paymentId);

        // Follow up with GET
        mockMvc.perform(get(BASE_URL + "/" + paymentId)
                        .with(user(customerId.toString()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId", is(paymentId)))
                .andExpect(jsonPath("$.sourceAccountId", is(accountId.getValue().toString())))
                .andExpect(jsonPath("$.paymentReference", is(reference)))
                .andExpect(jsonPath("$.beneficiaryAccountNumber", is("****6819")))
                .andExpect(jsonPath("$.status", anyOf(is("COMPLIANCE_HOLD"), is("ROUTING"), is("DISPATCHED"), is("SETTLED"))))
                .andExpect(jsonPath("$.reservationToken").doesNotExist());

        // Verify database reservation and account balance
        Map<String, Object> accountRow = jdbcTemplate.queryForMap(
                "SELECT available_balance, reserved_amount FROM banking.account WHERE account_id = ?",
                accountId.getValue()
        );
        assertThat((BigDecimal) accountRow.get("available_balance")).isEqualByComparingTo("4500.0000");
        assertThat((BigDecimal) accountRow.get("reserved_amount")).isEqualByComparingTo("500.0000");

        Integer reservationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM banking.fund_reservation WHERE account_id = ? AND status = 'ACTIVE'",
                Integer.class,
                accountId.getValue()
        );
        assertThat(reservationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 2: Idempotent replay of exact same POST payload returns 201 with existing payment and no duplicate reservation")
    void shouldHandleIdempotentReplayGracefully() throws Exception {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("3000.0000"));
        String reference = "PAY-IDEMP-" + shortId();

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountId.getValue(),
                reference,
                new BigDecimal("400.0000"),
                "USD",
                "British Telecom PLC",
                "GB29NWBK60161331926819",
                "NWBKGB2LXXX"
        );

        // First attempt
        MvcResult firstResult = mockMvc.perform(post(BASE_URL)
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String paymentId1 = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("paymentId").asText();

        // Second identical attempt (replay)
        MvcResult secondResult = mockMvc.perform(post(BASE_URL)
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String paymentId2 = objectMapper.readTree(secondResult.getResponse().getContentAsString()).get("paymentId").asText();
        assertThat(paymentId2).isEqualTo(paymentId1);

        // Ensure exactly one fund reservation exists
        Integer reservationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM banking.fund_reservation WHERE account_id = ? AND status = 'ACTIVE'",
                Integer.class,
                accountId.getValue()
        );
        assertThat(reservationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 3: Conflicting reference reuse on POST returns 409 Conflict")
    void shouldRejectConflictingReferenceReuseWith409() throws Exception {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("3000.0000"));
        String reference = "PAY-CONFLICT-" + shortId();

        InitiatePaymentRequest request1 = new InitiatePaymentRequest(
                accountId.getValue(),
                reference,
                new BigDecimal("400.0000"),
                "USD",
                "British Telecom PLC",
                "GB29NWBK60161331926819",
                "NWBKGB2LXXX"
        );

        mockMvc.perform(post(BASE_URL)
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated());

        // Conflicting second request: different amount
        InitiatePaymentRequest request2 = new InitiatePaymentRequest(
                accountId.getValue(),
                reference,
                new BigDecimal("999.0000"),
                "USD",
                "British Telecom PLC",
                "GB29NWBK60161331926819",
                "NWBKGB2LXXX"
        );

        mockMvc.perform(post(BASE_URL)
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString(reference)));
    }

    @Test
    @DisplayName("Scenario 4: Customer A attempts to initiate payment from Customer B's account returns 403 Forbidden")
    void customerCannotInitiatePaymentFromAnotherCustomersAccount() throws Exception {
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();
        AccountId accountB = seedCustomerAndAccount(customerB, "USD", new BigDecimal("5000.0000"));

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountB.getValue(),
                "PAY-THEFT-" + shortId(),
                new BigDecimal("500.0000"),
                "USD",
                "Attacker Merchant",
                "DE89370400440532013000",
                "DEUTDEDDFXX"
        );

        mockMvc.perform(post(BASE_URL)
                        .with(user(customerA.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")));
    }

    @Test
    @DisplayName("Scenario 5: Customer B attempting to GET Customer A's payment returns 404 Not Found (privacy anti-enumeration)")
    void customerCannotRetrieveAnotherCustomersPayment() throws Exception {
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();
        AccountId accountA = seedCustomerAndAccount(customerA, "USD", new BigDecimal("5000.0000"));

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountA.getValue(),
                "PAY-PRIVACY-" + shortId(),
                new BigDecimal("200.0000"),
                "USD",
                "British Telecom PLC",
                "GB29NWBK60161331926819",
                "NWBKGB2LXXX"
        );

        MvcResult result = mockMvc.perform(post(BASE_URL)
                        .with(user(customerA.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String paymentId = objectMapper.readTree(result.getResponse().getContentAsString()).get("paymentId").asText();

        // Customer B tries to GET Customer A's payment -> 404 Not Found
        mockMvc.perform(get(BASE_URL + "/" + paymentId)
                        .with(user(customerB.toString()).roles("CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", containsString(paymentId)));
    }

    @Test
    @DisplayName("Scenario 6: Payment initiation with insufficient funds creates payment record with REJECTED status")
    void paymentInitiationWithInsufficientFundsReturnsRejectedPayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        AccountId accountId = seedCustomerAndAccount(customerId, "USD", new BigDecimal("100.0000"));
        String reference = "PAY-NOFUNDS-" + shortId();

        InitiatePaymentRequest request = new InitiatePaymentRequest(
                accountId.getValue(),
                reference,
                new BigDecimal("9999.0000"),
                "USD",
                "British Telecom PLC",
                "GB29NWBK60161331926819",
                "NWBKGB2LXXX"
        );

        mockMvc.perform(post(BASE_URL)
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.statusReasonCode", is("INSUFFICIENT_FUNDS")));
    }
}
