package com.crosspaynet.operations.infrastructure.web;

import com.crosspaynet.KafkaTestContainerConfig;
import com.crosspaynet.PostgresTestContainerConfig;
import com.crosspaynet.operations.application.ReconciliationServiceImpl;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.DiscrepancyKey;
import com.crosspaynet.operations.domain.DiscrepancyStatus;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationDiscrepancy;
import com.crosspaynet.operations.domain.ReconciliationRun;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
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
class ReconciliationControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/operations/reconciliation";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private OperationsMapper operationsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        jdbcTemplate.execute("DELETE FROM operations.reconciliation_discrepancy");
        jdbcTemplate.execute("DELETE FROM operations.reconciliation_check_execution");
        jdbcTemplate.execute("DELETE FROM operations.reconciliation_run");
    }

    @Test
    @DisplayName("POST /runs triggers end-to-end reconciliation run, persists results, and returns 201 Created")
    void shouldTriggerReconciliationRunSuccessfully() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_URL + "/runs")
                        .with(user("lead-operator").roles("OPERATIONS"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.checksEvaluated", is(4)))
                .andExpect(jsonPath("$.discrepancyCount", is(0)))
                .andExpect(jsonPath("$.triggeredBy", is("lead-operator")))
                .andExpect(jsonPath("$.checks", hasSize(4)))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith(BASE_URL + "/runs/");

        // Verify database persistence
        assertThat(runRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /runs returns 409 Conflict when an active run is already in progress")
    void shouldRejectConcurrentRunWhenActiveRunInProgress() throws Exception {
        ReconciliationServiceImpl targetService = AopTestUtils.getTargetObject(reconciliationService);
        Field runLockField = ReconciliationServiceImpl.class.getDeclaredField("runLock");
        runLockField.setAccessible(true);
        ReentrantLock runLock = (ReentrantLock) runLockField.get(targetService);

        java.util.concurrent.CountDownLatch lockAcquired = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLock = new java.util.concurrent.CountDownLatch(1);

        Thread lockHolder = new Thread(() -> {
            runLock.lock();
            try {
                lockAcquired.countDown();
                releaseLock.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                runLock.unlock();
            }
        });

        lockHolder.start();
        lockAcquired.await();

        try {
            mockMvc.perform(post(BASE_URL + "/runs")
                            .with(user("second-operator").roles("OPERATIONS"))
                            .with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.error", is("Conflict")))
                    .andExpect(jsonPath("$.message", containsString("currently executing")));
        } finally {
            releaseLock.countDown();
            lockHolder.join();
        }
    }

    @Test
    @DisplayName("GET /runs retrieves paginated runs with deterministic ordering")
    void shouldListRunsDeterministically() throws Exception {
        Instant t0 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        ReconciliationRun run1 = ReconciliationRun.start(ReconciliationRunId.generate(), "op-1", t0);
        run1.complete(4, 0, t0.plus(1, ChronoUnit.SECONDS));
        runRepository.saveAndFlush(operationsMapper.toEntity(run1));

        ReconciliationRun run2 = ReconciliationRun.start(ReconciliationRunId.generate(), "op-2", t1);
        run2.complete(4, 0, t1.plus(1, ChronoUnit.SECONDS));
        runRepository.saveAndFlush(operationsMapper.toEntity(run2));

        mockMvc.perform(get(BASE_URL + "/runs?page=0&size=10")
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[0].runId", is(run2.getRunId().getValue().toString())))
                .andExpect(jsonPath("$.content[1].runId", is(run1.getRunId().getValue().toString())));
    }

    @Test
    @DisplayName("GET /runs/{runId} returns run details with check executions or 404 when absent")
    void shouldGetRunByIdOr404() throws Exception {
        // Trigger a run to generate real run and check executions
        MvcResult postResult = mockMvc.perform(post(BASE_URL + "/runs")
                        .with(user("ops").roles("OPERATIONS"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn();

        String location = postResult.getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        // Retrieve existing run
        mockMvc.perform(get(location)
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.checksEvaluated", is(4)))
                .andExpect(jsonPath("$.checks", hasSize(4)));

        // Retrieve non-existent run
        UUID missingId = UUID.randomUUID();
        mockMvc.perform(get(BASE_URL + "/runs/" + missingId)
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("GET /discrepancies filters by status, type, runId, and retrieves individual finding")
    void shouldFilterAndRetrieveDiscrepancies() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ReconciliationRunId runId = ReconciliationRunId.generate();
        ReconciliationRun run = ReconciliationRun.start(runId, "ops", now);
        run.complete(4, 1, now.plus(1, ChronoUnit.SECONDS));
        runRepository.saveAndFlush(operationsMapper.toEntity(run));

        DiscrepancyId discId = DiscrepancyId.generate();
        DiscrepancyKey discKey = DiscrepancyKey.of(DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW, "PAY-001");
        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.create(
                discId,
                discKey,
                runId,
                DiscrepancyType.STALE_IN_FLIGHT_WORKFLOW,
                "PAY-001",
                "Payment PAY-001 exceeded threshold",
                "{\"paymentId\":\"PAY-001\",\"ageMinutes\":120}",
                now
        );
        discrepancyRepository.saveAndFlush(operationsMapper.toEntity(discrepancy));

        // 1. List all discrepancies
        mockMvc.perform(get(BASE_URL + "/discrepancies")
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].discrepancyId", is(discId.getValue().toString())))
                .andExpect(jsonPath("$.content[0].status", is("OPEN")))
                .andExpect(jsonPath("$.content[0].discrepancyType", is("STALE_IN_FLIGHT_WORKFLOW")));

        // 2. Filter by matching status
        mockMvc.perform(get(BASE_URL + "/discrepancies?status=OPEN")
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        // 3. Filter by non-matching status
        mockMvc.perform(get(BASE_URL + "/discrepancies?status=ACKNOWLEDGED")
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        // 4. Filter by matching type
        mockMvc.perform(get(BASE_URL + "/discrepancies?type=STALE_IN_FLIGHT_WORKFLOW")
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        // 5. Filter by non-matching type
        mockMvc.perform(get(BASE_URL + "/discrepancies?type=PAYMENT_LEDGER_BREAK")
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        // 6. Filter by runId
        mockMvc.perform(get(BASE_URL + "/discrepancies?runId=" + runId.getValue())
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        // 7. Get single discrepancy by ID
        mockMvc.perform(get(BASE_URL + "/discrepancies/" + discId.getValue())
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancyId", is(discId.getValue().toString())))
                .andExpect(jsonPath("$.operationalEntityId", is("PAY-001")))
                .andExpect(jsonPath("$.summary", is("Payment PAY-001 exceeded threshold")));

        // 8. Get non-existent discrepancy
        UUID missingDiscId = UUID.randomUUID();
        mockMvc.perform(get(BASE_URL + "/discrepancies/" + missingDiscId)
                        .with(user("ops").roles("OPERATIONS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }
}
