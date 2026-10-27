-- V16__init_operations_schema.sql
-- Operations schema: reconciliation runs, check executions, and operational discrepancies (Phase 9A)

CREATE SCHEMA IF NOT EXISTS operations;

-- 1. Reconciliation Run Table
CREATE TABLE operations.reconciliation_run (
    run_id              UUID                        NOT NULL,
    version             BIGINT                      NOT NULL DEFAULT 0,
    status              VARCHAR(30)                 NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE    NOT NULL,
    completed_at        TIMESTAMP WITH TIME ZONE,
    checks_evaluated    INTEGER                     NOT NULL DEFAULT 0,
    discrepancy_count   INTEGER                     NOT NULL DEFAULT 0,
    triggered_by        VARCHAR(100)                NOT NULL,
    failure_reason      VARCHAR(1000),
    created_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_reconciliation_run PRIMARY KEY (run_id),

    CONSTRAINT chk_reconciliation_run_version_non_negative CHECK (
        version >= 0
    ),
    CONSTRAINT chk_reconciliation_run_status CHECK (
        status IN ('IN_PROGRESS', 'COMPLETED', 'PARTIAL_FAILURE', 'FAILED')
    ),
    CONSTRAINT chk_reconciliation_run_counts CHECK (
        checks_evaluated >= 0 AND discrepancy_count >= 0
    ),
    CONSTRAINT chk_reconciliation_run_triggered_by CHECK (
        char_length(btrim(triggered_by)) >= 1
        AND (char_length(triggered_by) + regexp_count(triggered_by, '[\U00010000-\U0010FFFF]')) <= 100
    ),
    CONSTRAINT chk_reconciliation_run_failure_reason CHECK (
        failure_reason IS NULL OR (
            char_length(btrim(failure_reason)) >= 1
            AND (char_length(failure_reason) + regexp_count(failure_reason, '[\U00010000-\U0010FFFF]')) <= 1000
        )
    ),
    CONSTRAINT chk_reconciliation_run_timestamps CHECK (
        updated_at >= created_at
        AND (completed_at IS NULL OR completed_at >= started_at)
    ),
    CONSTRAINT chk_reconciliation_run_status_coherence CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL AND failure_reason IS NULL)
        OR
        (status IN ('COMPLETED', 'PARTIAL_FAILURE', 'FAILED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_reconciliation_run_status 
    ON operations.reconciliation_run (status);

CREATE INDEX idx_reconciliation_run_started_at 
    ON operations.reconciliation_run (started_at);

-- 2. Reconciliation Check Execution Table
CREATE TABLE operations.reconciliation_check_execution (
    check_execution_id  UUID                        NOT NULL,
    run_id              UUID                        NOT NULL,
    check_name          VARCHAR(100)                NOT NULL,
    status              VARCHAR(30)                 NOT NULL,
    items_scanned       INTEGER                     NOT NULL DEFAULT 0,
    discrepancies_found INTEGER                     NOT NULL DEFAULT 0,
    error_summary       VARCHAR(1000),
    executed_at         TIMESTAMP WITH TIME ZONE    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_reconciliation_check_execution PRIMARY KEY (check_execution_id),
    CONSTRAINT fk_reconciliation_check_execution_run FOREIGN KEY (run_id) 
        REFERENCES operations.reconciliation_run (run_id) ON DELETE RESTRICT,

    CONSTRAINT chk_check_execution_status CHECK (
        status IN ('COMPLETED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT chk_check_execution_counts CHECK (
        items_scanned >= 0 AND discrepancies_found >= 0
    ),
    CONSTRAINT chk_check_execution_check_name CHECK (
        char_length(btrim(check_name)) >= 1
        AND (char_length(check_name) + regexp_count(check_name, '[\U00010000-\U0010FFFF]')) <= 100
    ),
    CONSTRAINT chk_check_execution_error_summary CHECK (
        error_summary IS NULL OR (
            char_length(btrim(error_summary)) >= 1
            AND (char_length(error_summary) + regexp_count(error_summary, '[\U00010000-\U0010FFFF]')) <= 1000
        )
    )
);

CREATE INDEX idx_reconciliation_check_execution_run 
    ON operations.reconciliation_check_execution (run_id);

CREATE INDEX idx_reconciliation_check_execution_executed_at 
    ON operations.reconciliation_check_execution (executed_at);

-- 3. Reconciliation Discrepancy Table
CREATE TABLE operations.reconciliation_discrepancy (
    discrepancy_id          UUID                        NOT NULL,
    version                 BIGINT                      NOT NULL DEFAULT 0,
    discrepancy_key         VARCHAR(150)                NOT NULL,
    first_run_id            UUID                        NOT NULL,
    latest_run_id           UUID                        NOT NULL,
    discrepancy_type        VARCHAR(50)                 NOT NULL,
    operational_entity_id   VARCHAR(100)                NOT NULL,
    status                  VARCHAR(30)                 NOT NULL,
    summary                 VARCHAR(500)                NOT NULL,
    details                 JSONB,
    first_detected_at       TIMESTAMP WITH TIME ZONE    NOT NULL,
    last_observed_at        TIMESTAMP WITH TIME ZONE    NOT NULL,
    consecutive_detections  INTEGER                     NOT NULL DEFAULT 1,
    resolved_at             TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_reconciliation_discrepancy PRIMARY KEY (discrepancy_id),
    CONSTRAINT uk_reconciliation_discrepancy_key UNIQUE (discrepancy_key),
    CONSTRAINT fk_discrepancy_first_run FOREIGN KEY (first_run_id) 
        REFERENCES operations.reconciliation_run (run_id) ON DELETE RESTRICT,
    CONSTRAINT fk_discrepancy_latest_run FOREIGN KEY (latest_run_id) 
        REFERENCES operations.reconciliation_run (run_id) ON DELETE RESTRICT,

    CONSTRAINT chk_discrepancy_version_non_negative CHECK (
        version >= 0
    ),
    CONSTRAINT chk_discrepancy_type CHECK (
        discrepancy_type IN (
            'STALE_IN_FLIGHT_WORKFLOW',
            'RESERVATION_PAYMENT_MISMATCH',
            'PAYMENT_LEDGER_BREAK',
            'INCOMPLETE_EVENT_PUBLICATION'
        )
    ),
    CONSTRAINT chk_discrepancy_status CHECK (
        status IN ('OPEN', 'RESOLVED_EXTERNALLY', 'ACKNOWLEDGED')
    ),
    CONSTRAINT chk_discrepancy_consecutive_detections CHECK (
        consecutive_detections >= 1
    ),
    CONSTRAINT chk_discrepancy_key_length CHECK (
        char_length(btrim(discrepancy_key)) >= 1
        AND (char_length(discrepancy_key) + regexp_count(discrepancy_key, '[\U00010000-\U0010FFFF]')) <= 150
    ),
    CONSTRAINT chk_discrepancy_entity_id CHECK (
        char_length(btrim(operational_entity_id)) >= 1
        AND (char_length(operational_entity_id) + regexp_count(operational_entity_id, '[\U00010000-\U0010FFFF]')) <= 100
    ),
    CONSTRAINT chk_discrepancy_summary CHECK (
        char_length(btrim(summary)) >= 1
        AND (char_length(summary) + regexp_count(summary, '[\U00010000-\U0010FFFF]')) <= 500
    ),
    CONSTRAINT chk_discrepancy_timestamps CHECK (
        updated_at >= created_at
        AND last_observed_at >= first_detected_at
    ),
    CONSTRAINT chk_discrepancy_resolution_coherence CHECK (
        (status = 'RESOLVED_EXTERNALLY' AND resolved_at IS NOT NULL AND resolved_at >= first_detected_at)
        OR
        (status IN ('OPEN', 'ACKNOWLEDGED') AND resolved_at IS NULL)
    )
);

CREATE INDEX idx_reconciliation_discrepancy_status 
    ON operations.reconciliation_discrepancy (status);

CREATE INDEX idx_reconciliation_discrepancy_type 
    ON operations.reconciliation_discrepancy (discrepancy_type);

CREATE INDEX idx_reconciliation_discrepancy_entity_id 
    ON operations.reconciliation_discrepancy (operational_entity_id);

CREATE INDEX idx_reconciliation_discrepancy_last_observed_at 
    ON operations.reconciliation_discrepancy (last_observed_at);
