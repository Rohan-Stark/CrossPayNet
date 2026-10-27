package com.crosspaynet.operations.application;

import com.crosspaynet.operations.application.check.CheckExecutionOutcome;
import com.crosspaynet.operations.application.check.ReconciliationCheckExecutor;
import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.OperationsConflictException;
import com.crosspaynet.operations.domain.ReconciliationCheckExecution;
import com.crosspaynet.operations.domain.ReconciliationCheckExecutionId;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRun;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transactional orchestrator for operational reconciliation runs.
 *
 * <p>Coordinates check executions with bounded transaction scopes, records partial failures
 * when individual checks fail, enforces mutex semantics across active runs, and updates
 * run completion invariants.
 */
@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationCheckExecutionRepository checkExecutionRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final OperationsMapper operationsMapper;
    private final List<ReconciliationCheckExecutor> checkExecutors;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock runLock = new ReentrantLock();

    public ReconciliationServiceImpl(
            ReconciliationRunRepository runRepository,
            ReconciliationCheckExecutionRepository checkExecutionRepository,
            ReconciliationDiscrepancyRepository discrepancyRepository,
            OperationsMapper operationsMapper,
            List<ReconciliationCheckExecutor> checkExecutors,
            PlatformTransactionManager transactionManager
    ) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository cannot be null.");
        this.checkExecutionRepository = Objects.requireNonNull(checkExecutionRepository, "checkExecutionRepository cannot be null.");
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository, "discrepancyRepository cannot be null.");
        this.operationsMapper = Objects.requireNonNull(operationsMapper, "operationsMapper cannot be null.");
        this.checkExecutors = checkExecutors != null ? List.copyOf(checkExecutors) : List.of();
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager cannot be null."));
    }

    @Override
    public ReconciliationRunView triggerRun(TriggerReconciliationRunCommand command) {
        Objects.requireNonNull(command, "command cannot be null.");

        if (!runLock.tryLock()) {
            throw new OperationsConflictException("A reconciliation run is currently executing.");
        }

        try {
            if (runRepository.existsByStatus(ReconciliationRunStatus.IN_PROGRESS.name())) {
                throw new OperationsConflictException("A reconciliation run is already in progress.");
            }

            return executeReconciliationRun(command);
        } finally {
            runLock.unlock();
        }
    }

    private ReconciliationRunView executeReconciliationRun(TriggerReconciliationRunCommand command) {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Step 1: Start and persist IN_PROGRESS run in short transaction
        transactionTemplate.executeWithoutResult(status -> {
            ReconciliationRun run = ReconciliationRun.start(runId, command.triggeredBy(), startedAt);
            runRepository.saveAndFlush(operationsMapper.toEntity(run));
        });

        int checksEvaluated = 0;
        int totalDiscrepancies = 0;
        boolean anyCheckFailed = false;

        try {
            // Step 2: Execute each check executor and persist check outcomes
            for (ReconciliationCheckExecutor executor : checkExecutors) {
                CheckExecutionOutcome outcome;
                try {
                    outcome = executor.execute(runId, startedAt);
                } catch (Exception ex) {
                    outcome = CheckExecutionOutcome.failure(
                            executor.getCheckName(),
                            0,
                            0,
                            sanitizeError(ex)
                    );
                }

                checksEvaluated++;
                totalDiscrepancies += outcome.discrepanciesFound();
                if (outcome.status() != ReconciliationCheckStatus.COMPLETED) {
                    anyCheckFailed = true;
                }

                final CheckExecutionOutcome finalOutcome = outcome;
                transactionTemplate.executeWithoutResult(status -> {
                    ReconciliationCheckExecution checkExecution = ReconciliationCheckExecution.create(
                            ReconciliationCheckExecutionId.generate(),
                            runId,
                            finalOutcome.checkName(),
                            finalOutcome.status(),
                            finalOutcome.itemsScanned(),
                            finalOutcome.discrepanciesFound(),
                            finalOutcome.errorSummary(),
                            Instant.now()
                    );
                    checkExecutionRepository.saveAndFlush(operationsMapper.toEntity(checkExecution));
                });
            }

            // Step 3: Complete run in short transaction
            final int finalChecksEvaluated = checksEvaluated;
            final int finalTotalDiscrepancies = totalDiscrepancies;
            final boolean finalAnyCheckFailed = anyCheckFailed;

            return transactionTemplate.execute(status -> {
                ReconciliationRunEntity entity = runRepository.findById(runId.getValue())
                        .orElseThrow(() -> new IllegalStateException("Run not found: " + runId));
                ReconciliationRun run = operationsMapper.toDomain(entity);
                Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                if (completedAt.isBefore(run.getStartedAt())) {
                    completedAt = run.getStartedAt();
                }

                if (finalAnyCheckFailed) {
                    run.markPartialFailure(
                            finalChecksEvaluated,
                            finalTotalDiscrepancies,
                            "One or more reconciliation checks encountered failures during execution",
                            completedAt
                    );
                } else {
                    run.complete(finalChecksEvaluated, finalTotalDiscrepancies, completedAt);
                }

                operationsMapper.updateEntity(run, entity);
                runRepository.saveAndFlush(entity);
                return operationsMapper.toView(entity);
            });

        } catch (Exception fatalEx) {
            // Run-level failure: mark run FAILED
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    runRepository.findById(runId.getValue()).ifPresent(entity -> {
                        ReconciliationRun run = operationsMapper.toDomain(entity);
                        run.fail(sanitizeError(fatalEx), Instant.now().truncatedTo(ChronoUnit.MICROS));
                        operationsMapper.updateEntity(run, entity);
                        runRepository.saveAndFlush(entity);
                    });
                });
            } catch (Exception suppress) {
                // preserve fatalEx
            }
            throw fatalEx;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReconciliationRunView> findRun(ReconciliationRunId runId) {
        if (runId == null) return Optional.empty();
        return runRepository.findById(runId.getValue())
                .map(operationsMapper::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationCheckExecutionView> findCheckExecutions(ReconciliationRunId runId) {
        if (runId == null) return List.of();
        return checkExecutionRepository.findByRunId(runId.getValue())
                .stream()
                .map(operationsMapper::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationDiscrepancyView> findDiscrepanciesForRun(ReconciliationRunId runId) {
        if (runId == null) return List.of();
        return discrepancyRepository.findByLatestRunId(runId.getValue())
                .stream()
                .map(operationsMapper::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReconciliationRunView> findLatestRun() {
        return runRepository.findTopByOrderByStartedAtDesc()
                .map(operationsMapper::toView);
    }

    private static String sanitizeError(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
