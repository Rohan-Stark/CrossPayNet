package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.operations.contracts.DiscrepancyQueryFilter;
import com.crosspaynet.operations.contracts.PagedResult;
import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.OperationsConflictException;
import com.crosspaynet.operations.domain.OperationsNotFoundException;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
class ReconciliationControllerSecurityTest {

    private static final String BASE_URL = "/api/v1/operations/reconciliation";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private ReconciliationService reconciliationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private ReconciliationRunView createSampleRunView(ReconciliationRunId runId, String triggeredBy) {
        Instant now = Instant.now();
        return new ReconciliationRunView(
                runId,
                ReconciliationRunStatus.COMPLETED,
                now,
                now,
                4,
                0,
                triggeredBy,
                null,
                now,
                now
        );
    }

    @Nested
    @DisplayName("Authentication & Authorization (RBAC) Controls")
    class AccessControlTests {

        @Test
        @DisplayName("Unauthenticated requests receive 401 Unauthorized across all endpoints")
        void unauthenticatedRequestsReceive401() throws Exception {
            UUID testId = UUID.randomUUID();

            mockMvc.perform(post(BASE_URL + "/runs").with(csrf()))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get(BASE_URL + "/runs"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get(BASE_URL + "/runs/" + testId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get(BASE_URL + "/discrepancies"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get(BASE_URL + "/discrepancies/" + testId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Non-operations roles (CUSTOMER, COMPLIANCE, TELLER) receive 403 Forbidden")
        void nonOperationsRolesReceive403() throws Exception {
            UUID testId = UUID.randomUUID();
            String[] forbiddenRoles = {"CUSTOMER", "COMPLIANCE", "TELLER"};

            for (String role : forbiddenRoles) {
                mockMvc.perform(post(BASE_URL + "/runs")
                                .with(user("user").roles(role))
                                .with(csrf()))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get(BASE_URL + "/runs")
                                .with(user("user").roles(role)))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get(BASE_URL + "/runs/" + testId)
                                .with(user("user").roles(role)))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get(BASE_URL + "/discrepancies")
                                .with(user("user").roles(role)))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get(BASE_URL + "/discrepancies/" + testId)
                                .with(user("user").roles(role)))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("ROLE_OPERATIONS can trigger runs and read all operational resources")
        void operationsRoleCanAccessAllEndpoints() throws Exception {
            UUID runUuid = UUID.randomUUID();
            ReconciliationRunId runId = ReconciliationRunId.of(runUuid);

            ReconciliationRunView runView = createSampleRunView(runId, "ops-officer");
            when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class)))
                    .thenReturn(runView);
            when(reconciliationService.findRun(runId))
                    .thenReturn(Optional.of(runView));
            when(reconciliationService.findCheckExecutions(runId))
                    .thenReturn(List.of());
            when(reconciliationService.findRuns(0, 20))
                    .thenReturn(new PagedResult<>(List.of(runView), 0, 20, 1, 1));

            mockMvc.perform(post(BASE_URL + "/runs")
                            .with(user("ops-officer").roles("OPERATIONS"))
                            .with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", BASE_URL + "/runs/" + runUuid))
                    .andExpect(jsonPath("$.runId", is(runUuid.toString())))
                    .andExpect(jsonPath("$.triggeredBy", is("ops-officer")));

            mockMvc.perform(get(BASE_URL + "/runs")
                            .with(user("ops-officer").roles("OPERATIONS")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)));

            mockMvc.perform(get(BASE_URL + "/runs/" + runUuid)
                            .with(user("ops-officer").roles("OPERATIONS")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId", is(runUuid.toString())));
        }

        @Test
        @DisplayName("ROLE_ADMIN can trigger runs and read all operational resources")
        void adminRoleCanAccessAllEndpoints() throws Exception {
            UUID runUuid = UUID.randomUUID();
            ReconciliationRunId runId = ReconciliationRunId.of(runUuid);

            ReconciliationRunView runView = createSampleRunView(runId, "admin-user");
            when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class)))
                    .thenReturn(runView);
            when(reconciliationService.findRuns(0, 20))
                    .thenReturn(new PagedResult<>(List.of(runView), 0, 20, 1, 1));

            mockMvc.perform(post(BASE_URL + "/runs")
                            .with(user("admin-user").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.triggeredBy", is("admin-user")));

            mockMvc.perform(get(BASE_URL + "/runs")
                            .with(user("admin-user").roles("ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Triggered run derives operator identity strictly from authenticated SecurityContext")
        void operatorIdentityDerivedFromSecurityContext() throws Exception {
            UUID runUuid = UUID.randomUUID();
            ReconciliationRunId runId = ReconciliationRunId.of(runUuid);

            when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class)))
                    .thenReturn(createSampleRunView(runId, "special-auditor"));
            when(reconciliationService.findCheckExecutions(runId))
                    .thenReturn(List.of());

            mockMvc.perform(post(BASE_URL + "/runs")
                            .with(user("special-auditor").roles("OPERATIONS"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operator\":\"malicious-override\"}"))
                    .andExpect(status().isCreated());

            ArgumentCaptor<TriggerReconciliationRunCommand> cmdCaptor =
                    ArgumentCaptor.forClass(TriggerReconciliationRunCommand.class);
            verify(reconciliationService).triggerRun(cmdCaptor.capture());
            assertThat(cmdCaptor.getValue().triggeredBy()).isEqualTo("special-auditor");
        }
    }

    @Nested
    @DisplayName("Error Handling, Conflict & Validation")
    class ErrorAndValidationTests {

        @Test
        @DisplayName("Active run contention maps to 409 Conflict with sanitized ApiErrorResponse")
        void activeRunContentionReturns409Conflict() throws Exception {
            when(reconciliationService.triggerRun(any(TriggerReconciliationRunCommand.class)))
                    .thenThrow(new OperationsConflictException("A reconciliation run is already in progress."));

            mockMvc.perform(post(BASE_URL + "/runs")
                            .with(user("ops").roles("OPERATIONS"))
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.error", is("Conflict")))
                    .andExpect(jsonPath("$.message", containsString("already in progress")))
                    .andExpect(jsonPath("$.path", is(BASE_URL + "/runs")));
        }

        @Test
        @DisplayName("Missing run ID maps to 404 Not Found")
        void missingRunReturns404() throws Exception {
            UUID runUuid = UUID.randomUUID();
            when(reconciliationService.findRun(ReconciliationRunId.of(runUuid)))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get(BASE_URL + "/runs/" + runUuid)
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.error", is("Not Found")))
                    .andExpect(jsonPath("$.message", containsString("Reconciliation run not found")));
        }

        @Test
        @DisplayName("Missing discrepancy ID maps to 404 Not Found")
        void missingDiscrepancyReturns404() throws Exception {
            UUID discrepancyUuid = UUID.randomUUID();
            when(reconciliationService.findDiscrepancy(DiscrepancyId.of(discrepancyUuid)))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get(BASE_URL + "/discrepancies/" + discrepancyUuid)
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.error", is("Not Found")))
                    .andExpect(jsonPath("$.message", containsString("Discrepancy not found")));
        }

        @Test
        @DisplayName("Negative page number returns 400 Bad Request")
        void negativePageReturns400() throws Exception {
            when(reconciliationService.findRuns(-1, 20))
                    .thenThrow(new IllegalArgumentException("Page index must not be negative: -1"));

            mockMvc.perform(get(BASE_URL + "/runs?page=-1")
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")));
        }

        @Test
        @DisplayName("Invalid page size > 100 returns 400 Bad Request")
        void oversizePageReturns400() throws Exception {
            when(reconciliationService.findRuns(0, 101))
                    .thenThrow(new IllegalArgumentException("Page size must be between 1 and 100: 101"));

            mockMvc.perform(get(BASE_URL + "/runs?size=101")
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")));
        }

        @Test
        @DisplayName("Invalid discrepancy status filter returns 400 Bad Request")
        void invalidDiscrepancyStatusReturns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/discrepancies?status=BOGUS_STATUS")
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Invalid discrepancy status 'BOGUS_STATUS'")));
        }

        @Test
        @DisplayName("Invalid discrepancy type filter returns 400 Bad Request")
        void invalidDiscrepancyTypeReturns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/discrepancies?type=NONEXISTENT_TYPE")
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Invalid discrepancy type 'NONEXISTENT_TYPE'")));
        }

        @Test
        @DisplayName("Malformed UUID path variable returns 400 Bad Request")
        void malformedUuidReturns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/runs/not-a-valid-uuid")
                            .with(user("ops").roles("OPERATIONS")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.error", is("Bad Request")))
                    .andExpect(jsonPath("$.message", containsString("Invalid parameter 'runId'")));
        }
    }
}
