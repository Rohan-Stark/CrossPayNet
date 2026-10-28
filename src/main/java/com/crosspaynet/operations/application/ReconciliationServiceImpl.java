package com.crosspaynet.operations.application;

import com.crosspaynet.operations.application.check.CheckExecutionOutcome;
import com.crosspaynet.operations.application.check.ReconciliationCheckExecutor;
import com.crosspaynet.operations.contracts.DiscrepancyQueryFilter;
import com.crosspaynet.operations.contracts.PagedResult;
import com.crosspaynet.operations.contracts.ReconciliationCheckExecutionView;
import com.crosspaynet.operations.contracts.ReconciliationDiscrepancyView;
import com.crosspaynet.operations.contracts.ReconciliationRunView;
import com.crosspaynet.operations.contracts.ReconciliationService;
import com.crosspaynet.operations.contracts.TriggerReconciliationRunCommand;
import com.crosspaynet.operations.domain.DiscrepancyId;
import com.crosspaynet.operations.domain.OperationsConflictException;
import com.crosspaynet.operations.domain.ReconciliationCheckExecution;
import com.crosspaynet.operations.domain.ReconciliationCheckExecutionId;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRun;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.domain.ReconciliationRunStatus;
import com.crosspaynet.operations.infrastructure.persistence.OperationsMapper;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationCheckExecutionRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationDiscrepancyRepository;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunEntity;
import com.crosspaynet.operations.infrastructure.persistence.ReconciliationRunRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<UUID> activeRunId = new AtomicReference<>();

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
            // Recover any abandoned runs left over from prior process termination
            recoverAbandonedRuns();

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
        activeRunId.set(runId.getValue());

        try {
            // Step 1: Start and persist IN_PROGRESS run in short transaction
            transactionTemplate.executeWithoutResult(status -> {
                ReconciliationRun run = ReconciliationRun.start(runId, command.triggeredBy(), startedAt);
                runRepository.saveAndFlush(operationsMapper.toEntity(run));
            });

            int checksEvaluated = 0;
            int totalDiscrepancies = 0;
            boolean anyCheckFailed = false;

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
                    ReconciliationCheckExecution execution = ReconciliationCheckExecution.create(
                            ReconciliationCheckExecutionId.generate(),
                            runId,
                            finalOutcome.checkName(),
                            finalOutcome.status(),
                            finalOutcome.itemsScanned(),
                            finalOutcome.discrepanciesFound(),
                            finalOutcome.errorSummary(),
                            startedAt
                    );
                    checkExecutionRepository.saveAndFlush(operationsMapper.toEntity(execution));
                });
            }

            // Step 3: Complete run with final status determination
            final int finalChecksEvaluated = checksEvaluated;
            final int finalTotalDiscrepancies = totalDiscrepancies;
            final boolean finalAnyCheckFailed = anyCheckFailed;
            Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

            return transactionTemplate.execute(status -> {
                ReconciliationRunEntity entity = runRepository.findById(runId.getValue())
                        .orElseThrow(() -> new IllegalStateException("Run entity not found: " + runId));
                ReconciliationRun run = operationsMapper.toDomain(entity);

                if (finalAnyCheckFailed) {
                    run.markPartialFailure(
                            finalChecksEvaluated,
                            finalTotalDiscrepancies,
                            "One or more reconciliation checks encountered failures during execution",
                            completedAt
                    );
                } else {
                    run.complete(
                            finalChecksEvaluated,
                            finalTotalDiscrepancies,
                            completedAt
                    );
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
        } finally {
            activeRunId.set(null);
        }
    }

    @Override
    public int recoverAbandonedRuns() {
        List<ReconciliationRunEntity> inProgressRuns = runRepository.findByStatus(ReconciliationRunStatus.IN_PROGRESS.name());
        if (inProgressRuns.isEmpty()) {
            return 0;
        }

        UUID currentlyActive = activeRunId.get();
        int recoveredCount = 0;

        for (ReconciliationRunEntity entity : inProgressRuns) {
            // Never mark an actively executing run as failed
            if (currentlyActive != null && entity.getRunId().equals(currentlyActive)) {
                continue;
            }

            boolean recovered = recoverRun(entity.getRunId());
            if (recovered) {
                recoveredCount++;
            }
        }
        return recoveredCount;
    }

    private boolean recoverRun(UUID runId) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                return runRepository.findById(runId).map(entity -> {
                    if (!ReconciliationRunStatus.IN_PROGRESS.name().equals(entity.getStatus())) {
                        return false;
                    }
                    ReconciliationRun run = operationsMapper.toDomain(entity);
                    run.fail(
                            "Abandoned run recovered: execution interrupted by process termination or restart",
                            Instant.now().truncatedTo(ChronoUnit.MICROS)
                    );
                    operationsMapper.updateEntity(run, entity);
                    runRepository.saveAndFlush(entity);
                    return true;
                }).orElse(false);
            }));
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return false;
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
    public PagedResult<ReconciliationRunView> findRuns(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt", "runId"));
        Page<ReconciliationRunEntity> entityPage = runRepository.findAll(pageable);
        List<ReconciliationRunView> content = entityPage.getContent().stream()
                .map(operationsMapper::toView)
                .toList();

        return new PagedResult<>(
                content,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages()
        );
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
    public Optional<ReconciliationDiscrepancyView> findDiscrepancy(DiscrepancyId discrepancyId) {
        if (discrepancyId == null) {
            throw new IllegalArgumentException("discrepancyId cannot be null.");
        }
        return discrepancyRepository.findById(discrepancyId.getValue())
                .map(operationsMapper::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<ReconciliationDiscrepancyView> findDiscrepancies(DiscrepancyQueryFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter cannot be null.");
        }

        Pageable pageable = PageRequest.of(filter.page(), filter.size(), Sort.by(Sort.Direction.DESC, "lastObservedAt", "discrepancyId"));

        Specification<ReconciliationDiscrepancyEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status().name()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("discrepancyType"), filter.type().name()));
            }
            if (filter.runId() != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("latestRunId"), filter.runId().getValue()),
                        cb.equal(root.get("firstRunId"), filter.runId().getValue())
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<ReconciliationDiscrepancyEntity> entityPage = discrepancyRepository.findAll(spec, pageable);
        List<ReconciliationDiscrepancyView> content = entityPage.getContent().stream()
                .map(operationsMapper::toView)
                .toList();

        return new PagedResult<>(
                content,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages()
        );
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
