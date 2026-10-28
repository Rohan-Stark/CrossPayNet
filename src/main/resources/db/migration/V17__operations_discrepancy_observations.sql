-- V17__operations_discrepancy_observations.sql
-- Operations schema: durable discrepancy observation history (Phase 9B Checkpoint 1)

CREATE TABLE operations.reconciliation_discrepancy_observation (
    observation_id          UUID                        NOT NULL,
    discrepancy_id          UUID                        NOT NULL,
    run_id                  UUID                        NOT NULL,
    discrepancy_type        VARCHAR(50)                 NOT NULL,
    status_at_observation  VARCHAR(30)                 NOT NULL,
    summary                 VARCHAR(500)                NOT NULL,
    details                 JSONB,
    observed_at             TIMESTAMP WITH TIME ZONE    NOT NULL,
    provenance              VARCHAR(30)                 NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_discrepancy_observation PRIMARY KEY (observation_id),
    CONSTRAINT uk_discrepancy_run_observation UNIQUE (discrepancy_id, run_id),
    CONSTRAINT fk_observation_discrepancy FOREIGN KEY (discrepancy_id)
        REFERENCES operations.reconciliation_discrepancy (discrepancy_id) ON DELETE RESTRICT,
    CONSTRAINT fk_observation_run FOREIGN KEY (run_id)
        REFERENCES operations.reconciliation_run (run_id) ON DELETE RESTRICT,

    CONSTRAINT chk_observation_status CHECK (
        status_at_observation IN ('OPEN', 'RESOLVED_EXTERNALLY', 'ACKNOWLEDGED')
    ),
    CONSTRAINT chk_observation_type CHECK (
        discrepancy_type IN (
            'STALE_IN_FLIGHT_WORKFLOW',
            'RESERVATION_PAYMENT_MISMATCH',
            'PAYMENT_LEDGER_BREAK',
            'INCOMPLETE_EVENT_PUBLICATION'
        )
    ),
    CONSTRAINT chk_observation_provenance CHECK (
        provenance IN ('LIVE', 'LEGACY_RECONSTRUCTED')
    ),
    CONSTRAINT chk_observation_summary CHECK (
        char_length(btrim(summary)) >= 1
        AND (char_length(summary) + regexp_count(summary, '[\U00010000-\U0010FFFF]')) <= 500
    )
);

CREATE INDEX idx_reconciliation_obs_run_id 
    ON operations.reconciliation_discrepancy_observation (run_id);

CREATE INDEX idx_reconciliation_obs_discrepancy_id 
    ON operations.reconciliation_discrepancy_observation (discrepancy_id);

CREATE INDEX idx_reconciliation_obs_observed_at 
    ON operations.reconciliation_discrepancy_observation (observed_at);

-- Honest Backfill of Existing V16 Discrepancies
-- 1. Initial observation for every existing discrepancy (first_run_id)
INSERT INTO operations.reconciliation_discrepancy_observation (
    observation_id,
    discrepancy_id,
    run_id,
    discrepancy_type,
    status_at_observation,
    summary,
    details,
    observed_at,
    provenance,
    created_at
)
SELECT
    gen_random_uuid(),
    d.discrepancy_id,
    d.first_run_id,
    d.discrepancy_type,
    'OPEN',
    d.summary,
    CASE WHEN d.first_run_id = d.latest_run_id THEN d.details ELSE NULL END,
    d.first_detected_at,
    'LEGACY_RECONSTRUCTED',
    d.created_at
FROM operations.reconciliation_discrepancy d;

-- 2. Latest observation for existing discrepancies where latest_run_id differs from first_run_id
INSERT INTO operations.reconciliation_discrepancy_observation (
    observation_id,
    discrepancy_id,
    run_id,
    discrepancy_type,
    status_at_observation,
    summary,
    details,
    observed_at,
    provenance,
    created_at
)
SELECT
    gen_random_uuid(),
    d.discrepancy_id,
    d.latest_run_id,
    d.discrepancy_type,
    d.status,
    d.summary,
    d.details,
    d.last_observed_at,
    'LEGACY_RECONSTRUCTED',
    d.updated_at
FROM operations.reconciliation_discrepancy d
WHERE d.latest_run_id <> d.first_run_id
ON CONFLICT (discrepancy_id, run_id) DO NOTHING;
