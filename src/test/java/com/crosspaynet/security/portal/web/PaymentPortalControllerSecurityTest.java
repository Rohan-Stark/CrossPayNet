package com.crosspaynet.security.portal.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.BeneficiaryDetails;
import com.crosspaynet.payment.contracts.BicCode;
import com.crosspaynet.payment.contracts.InitiatePaymentCommand;
import com.crosspaynet.payment.contracts.PagedPayments;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstruction;
import com.crosspaynet.payment.contracts.PaymentNotFoundException;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentReferenceConflictException;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class PaymentPortalControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/portal/payments";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SecuredPaymentPortalService portalService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private PaymentView createSamplePaymentView(
            PaymentId paymentId,
            AccountId accountId,
            PaymentReference reference,
            String beneficiaryAccount,
            PaymentStatus status) {
        Instant now = Instant.now();
        PaymentInstruction instruction = new PaymentInstruction(
                accountId,
                reference,
                new BeneficiaryDetails("Acme Corp", beneficiaryAccount, BicCode.of("DEUTDEDDFXX")),
                Money.of("500.0000", "USD")
        );
        return new PaymentView(
                paymentId,
                instruction,
                status,
                new ReservationToken(UUID.randomUUID()),
                "ROUTE-REF-123",
                "EXT-DISP-456",
                UUID.randomUUID(),
                null,
                null,
                now,
                now
        );
    }

    private InitiatePaymentRequest createValidRequest(UUID sourceAccountId, String paymentReference) {
        return new InitiatePaymentRequest(
                sourceAccountId,
                paymentReference,
                new BigDecimal("500.0000"),
                "USD",
                "Acme Corp",
                "DE89370400440532013000",
                "DEUTDEDDFXX"
        );
    }

    @Nested
    @DisplayName("Authentication and Role-Based Access Control")
    class AccessControlTests {

        @Test
        @DisplayName("Unauthenticated requests receive 401 Unauthorized")
        void unauthenticatedRequestsReceive401() throws Exception {
            UUID testPaymentId = UUID.randomUUID();

            mockMvc.perform(post(BASE_URL)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createValidRequest(UUID.randomUUID(), "REF-100"))))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get(BASE_URL + "/" + testPaymentId))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(portalService);
        }

        @Test
        @DisplayName("Non-customer roles receive 403 Forbidden")
        void nonCustomerRolesReceive403() throws Exception {
            UUID testPaymentId = UUID.randomUUID();
            String[] forbiddenRoles = {"OPERATIONS", "ADMIN", "COMPLIANCE", "TELLER"};

            for (String role : forbiddenRoles) {
                mockMvc.perform(post(BASE_URL)
                                .with(user("staff-user").roles(role))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createValidRequest(UUID.randomUUID(), "REF-" + role))))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get(BASE_URL + "/" + testPaymentId)
                                .with(user("staff-user").roles(role)))
                        .andExpect(status().isForbidden());
            }

            verifyNoInteractions(portalService);
        }
    }

    @Nested
    @DisplayName("Authorized Customer Payment Initiation (POST /api/v1/portal/payments)")
    class InitiatePaymentTests {

        @Test
        @DisplayName("Customer can initiate payment and receive 201 Created with Location header and masked representation")
        void customerInitiatePaymentSuccess() throws Exception {
            UUID customerId = UUID.randomUUID();
            UUID sourceAccountId = UUID.randomUUID();
            PaymentId paymentId = PaymentId.generate();
            PaymentReference reference = PaymentReference.of("REF-PORTAL-01");

            PaymentView sampleView = createSamplePaymentView(
                    paymentId,
                    new AccountId(sourceAccountId),
                    reference,
                    "DE89370400440532013000",
                    PaymentStatus.COMPLIANCE_HOLD
            );

            when(portalService.initiatePayment(any(InitiatePaymentCommand.class))).thenReturn(sampleView);

            InitiatePaymentRequest request = createValidRequest(sourceAccountId, reference.getValue());

            mockMvc.perform(post(BASE_URL)
                            .with(user(customerId.toString()).roles("CUSTOMER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", BASE_URL + "/" + paymentId.getValue()))
                    .andExpect(jsonPath("$.paymentId", is(paymentId.getValue().toString())))
                    .andExpect(jsonPath("$.sourceAccountId", is(sourceAccountId.toString())))
                    .andExpect(jsonPath("$.paymentReference", is(reference.getValue())))
                    .andExpect(jsonPath("$.beneficiaryName", is("Acme Corp")))
                    // Account DE89370400440532013000 has length 22, last 4 is 3000 -> ****3000
                    .andExpect(jsonPath("$.beneficiaryAccountNumber", is("****3000")))
                    .andExpect(jsonPath("$.beneficiaryBankBic", is("DEUTDEDDFXX")))
                    .andExpect(jsonPath("$.amount", is(500.0000)))
                    .andExpect(jsonPath("$.currency", is("USD")))
                    .andExpect(jsonPath("$.status", is("COMPLIANCE_HOLD")))
                    // Technical internals must NOT be exposed
                    .andExpect(jsonPath("$.reservationToken").doesNotExist())
                    .andExpect(jsonPath("$.ledgerTransactionId").doesNotExist())
                    .andExpect(jsonPath("$.routeReference").doesNotExist())
                    .andExpect(jsonPath("$.externalDispatchReference").doesNotExist());

            verify(portalService).initiatePayment(any(InitiatePaymentCommand.class));
        }

        @Test
        @DisplayName("Reused payment reference returns 409 Conflict")
        void duplicateReferenceReturns409() throws Exception {
            UUID customerId = UUID.randomUUID();
            UUID sourceAccountId = UUID.randomUUID();
            PaymentReference reference = PaymentReference.of("REF-DUPLICATE-01");

            when(portalService.initiatePayment(any(InitiatePaymentCommand.class)))
                    .thenThrow(new PaymentReferenceConflictException("Payment reference already exists with conflicting instruction: " + reference.getValue()));

            InitiatePaymentRequest request = createValidRequest(sourceAccountId, reference.getValue());

            mockMvc.perform(post(BASE_URL)
                            .with(user(customerId.toString()).roles("CUSTOMER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.error", is("Conflict")))
                    .andExpect(jsonPath("$.message", containsString("already exists")));
        }

        @Test
        @DisplayName("Invalid or missing request fields return 400 Bad Request")
        void invalidRequestBodyReturns400() throws Exception {
            UUID customerId = UUID.randomUUID();

            // Missing sourceAccountId
            InitiatePaymentRequest missingSource = new InitiatePaymentRequest(
                    null,
                    "REF-INV-1",
                    new BigDecimal("100.00"),
                    "USD",
                    "Acme Corp",
                    "DE89370400440532013000",
                    "DEUTDEDDFXX"
            );
            mockMvc.perform(post(BASE_URL)
                            .with(user(customerId.toString()).roles("CUSTOMER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(missingSource)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("sourceAccountId")));

            // Non-positive amount
            InitiatePaymentRequest negativeAmount = new InitiatePaymentRequest(
                    UUID.randomUUID(),
                    "REF-INV-2",
                    new BigDecimal("-50.00"),
                    "USD",
                    "Acme Corp",
                    "DE89370400440532013000",
                    "DEUTDEDDFXX"
            );
            mockMvc.perform(post(BASE_URL)
                            .with(user(customerId.toString()).roles("CUSTOMER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(negativeAmount)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("positive")));

            // Unsupported currency
            InitiatePaymentRequest unsupportedCurrency = new InitiatePaymentRequest(
                    UUID.randomUUID(),
                    "REF-INV-3",
                    new BigDecimal("100.00"),
                    "INVALID",
                    "Acme Corp",
                    "DE89370400440532013000",
                    "DEUTDEDDFXX"
            );
            mockMvc.perform(post(BASE_URL)
                            .with(user(customerId.toString()).roles("CUSTOMER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(unsupportedCurrency)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("currency")));

            // Invalid BIC
            InitiatePaymentRequest invalidBic = new InitiatePaymentRequest(
                    UUID.randomUUID(),
                    "REF-INV-4",
                    new BigDecimal("100.00"),
                    "USD",
                    "Acme Corp",
                    "DE89370400440532013000",
                    "BADBIC"
            );
            mockMvc.perform(post(BASE_URL)
                            .with(user(customerId.toString()).roles("CUSTOMER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidBic)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("BIC")));
        }
    }

    @Nested
    @DisplayName("Authorized Customer Payment Query (GET /api/v1/portal/payments/{paymentId})")
    class GetPaymentTests {

        @Test
        @DisplayName("Customer can retrieve their payment and receive 200 OK with masked representation")
        void customerGetPaymentSuccess() throws Exception {
            UUID customerId = UUID.randomUUID();
            UUID sourceAccountId = UUID.randomUUID();
            PaymentId paymentId = PaymentId.generate();
            PaymentReference reference = PaymentReference.of("REF-PORTAL-02");

            PaymentView sampleView = createSamplePaymentView(
                    paymentId,
                    new AccountId(sourceAccountId),
                    reference,
                    "GB29NWBK60161331926819",
                    PaymentStatus.SETTLED
            );

            when(portalService.findPayment(paymentId)).thenReturn(sampleView);

            mockMvc.perform(get(BASE_URL + "/" + paymentId.getValue())
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentId", is(paymentId.getValue().toString())))
                    .andExpect(jsonPath("$.sourceAccountId", is(sourceAccountId.toString())))
                    .andExpect(jsonPath("$.paymentReference", is(reference.getValue())))
                    .andExpect(jsonPath("$.beneficiaryName", is("Acme Corp")))
                    // Account GB29NWBK60161331926819 last 4 is 6819 -> ****6819
                    .andExpect(jsonPath("$.beneficiaryAccountNumber", is("****6819")))
                    .andExpect(jsonPath("$.beneficiaryBankBic", is("DEUTDEDDFXX")))
                    .andExpect(jsonPath("$.amount", is(500.0000)))
                    .andExpect(jsonPath("$.currency", is("USD")))
                    .andExpect(jsonPath("$.status", is("SETTLED")))
                    .andExpect(jsonPath("$.statusReasonCode").value(nullValue()))
                    .andExpect(jsonPath("$.statusReasonDetail").value(nullValue()))
                    .andExpect(jsonPath("$.reservationToken").doesNotExist())
                    .andExpect(jsonPath("$.ledgerTransactionId").doesNotExist())
                    .andExpect(jsonPath("$.routeReference").doesNotExist())
                    .andExpect(jsonPath("$.externalDispatchReference").doesNotExist());

            verify(portalService).findPayment(paymentId);
        }

        @Test
        @DisplayName("Customer attempting to read another customer's payment receives 404 Not Found (privacy anti-enumeration)")
        void readingOtherCustomerPaymentReturns404() throws Exception {
            UUID customerId = UUID.randomUUID();
            PaymentId foreignPaymentId = PaymentId.generate();

            when(portalService.findPayment(foreignPaymentId))
                    .thenThrow(new AccessDeniedException("Authenticated customer does not own payment"));

            mockMvc.perform(get(BASE_URL + "/" + foreignPaymentId.getValue())
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.error", is("Not Found")))
                    .andExpect(jsonPath("$.message", containsString(foreignPaymentId.getValue().toString())));
        }

        @Test
        @DisplayName("Non-existent payment returns 404 Not Found")
        void nonExistentPaymentReturns404() throws Exception {
            UUID customerId = UUID.randomUUID();
            PaymentId nonExistentPaymentId = PaymentId.generate();

            when(portalService.findPayment(nonExistentPaymentId))
                    .thenThrow(new PaymentNotFoundException(nonExistentPaymentId));

            mockMvc.perform(get(BASE_URL + "/" + nonExistentPaymentId.getValue())
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.error", is("Not Found")))
                    .andExpect(jsonPath("$.message", containsString(nonExistentPaymentId.getValue().toString())));
        }

        @Test
        @DisplayName("Malformed paymentId UUID path parameter returns 400 Bad Request")
        void malformedUuidReturns400() throws Exception {
            UUID customerId = UUID.randomUUID();

            mockMvc.perform(get(BASE_URL + "/not-a-valid-uuid")
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Invalid parameter 'paymentId'")));
        }
    }

    @Nested
    @DisplayName("Customer Payment History (GET /api/v1/portal/payments)")
    class PaymentHistory {

        @Test
        @DisplayName("Unauthenticated request to list payments returns 401 Unauthorized")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(portalService);
        }

        @Test
        @DisplayName("Non-customer roles are rejected with 403 Forbidden")
        void nonCustomerRolesForbidden() throws Exception {
            for (String role : List.of("OPERATIONS", "COMPLIANCE", "ADMIN", "TELLER")) {
                mockMvc.perform(get(BASE_URL).with(user("user").roles(role)))
                        .andExpect(status().isForbidden());
            }

            verifyNoInteractions(portalService);
        }

        @Test
        @DisplayName("Customer gets paginated payments with default page 0 and size 20")
        void defaultPaginationReturnsPayments() throws Exception {
            UUID customerId = UUID.randomUUID();
            AccountId accountId = AccountId.generate();
            PaymentView payment = createSamplePaymentView(
                    PaymentId.generate(),
                    accountId,
                    new PaymentReference("REF-HIST-1"),
                    "DE89370400440532016819",
                    PaymentStatus.SETTLED
            );

            when(portalService.findCustomerPayments(0, 20))
                    .thenReturn(new PagedPayments(List.of(payment), 0, 20, 1L, 1));

            mockMvc.perform(get(BASE_URL).with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page", is(0)))
                    .andExpect(jsonPath("$.size", is(20)))
                    .andExpect(jsonPath("$.totalElements", is(1)))
                    .andExpect(jsonPath("$.totalPages", is(1)))
                    .andExpect(jsonPath("$.content[0].paymentId", is(payment.paymentId().getValue().toString())))
                    .andExpect(jsonPath("$.content[0].status", is("SETTLED")))
                    .andExpect(jsonPath("$.content[0].beneficiaryAccountNumber", is("****6819")));

            verify(portalService).findCustomerPayments(0, 20);
        }

        @Test
        @DisplayName("Negative page index returns 400 Bad Request")
        void negativePageReturns400() throws Exception {
            UUID customerId = UUID.randomUUID();

            mockMvc.perform(get(BASE_URL + "?page=-1&size=20")
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(portalService);
        }

        @Test
        @DisplayName("Size out of bounds returns 400 Bad Request")
        void sizeOutOfBoundsReturns400() throws Exception {
            UUID customerId = UUID.randomUUID();

            mockMvc.perform(get(BASE_URL + "?page=0&size=0")
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get(BASE_URL + "?page=0&size=101")
                            .with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(portalService);
        }

        @Test
        @DisplayName("Returns empty page when customer has no payments")
        void emptyPageWhenNoPayments() throws Exception {
            UUID customerId = UUID.randomUUID();

            when(portalService.findCustomerPayments(0, 20))
                    .thenReturn(PagedPayments.empty(0, 20));

            mockMvc.perform(get(BASE_URL).with(user(customerId.toString()).roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page", is(0)))
                    .andExpect(jsonPath("$.size", is(20)))
                    .andExpect(jsonPath("$.totalElements", is(0)))
                    .andExpect(jsonPath("$.totalPages", is(0)))
                    .andExpect(jsonPath("$.content", org.hamcrest.Matchers.empty()));

            verify(portalService).findCustomerPayments(0, 20);
        }
    }
}
