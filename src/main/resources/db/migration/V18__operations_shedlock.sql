-- V18__operations_shedlock.sql
-- Operations schema: ShedLock distributed lock table for scheduled reconciliation (Phase 9B Checkpoint 2)

CREATE TABLE operations.shedlock (
    name        VARCHAR(64)                 NOT NULL,
    lock_until  TIMESTAMP WITH TIME ZONE    NOT NULL,
    locked_at   TIMESTAMP WITH TIME ZONE    NOT NULL,
    locked_by   VARCHAR(255)                NOT NULL,

    CONSTRAINT pk_operations_shedlock PRIMARY KEY (name)
);
