-- V15__init_compliance_schema.sql
-- Compliance schema and compliance_review aggregate table with strict invariants (Phase 8)

CREATE SCHEMA IF NOT EXISTS compliance;

CREATE TABLE compliance.compliance_review (
    review_id       UUID                        NOT NULL,
    version         BIGINT                      NOT NULL DEFAULT 0,
    payment_id      UUID                        NOT NULL,
    status          VARCHAR(16)                 NOT NULL,
    risk_score      INTEGER                     NOT NULL,
    flag_reasons    VARCHAR(1000)               NOT NULL DEFAULT '',
    reviewer_id     VARCHAR(100),
    decision        VARCHAR(16),
    justification   VARCHAR(1000),
    decided_at      TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE    NOT NULL,

    CONSTRAINT pk_compliance_review PRIMARY KEY (review_id),
    CONSTRAINT uk_compliance_review_payment_id UNIQUE (payment_id),

    CONSTRAINT chk_compliance_review_version_non_negative CHECK (
        version >= 0
    ),
    CONSTRAINT chk_compliance_review_status CHECK (
        status IN ('PENDING', 'CLEARED', 'REJECTED')
    ),
    CONSTRAINT chk_compliance_review_decision CHECK (
        decision IS NULL OR decision IN ('RELEASE', 'REJECT')
    ),
    CONSTRAINT chk_compliance_review_risk_score CHECK (
        risk_score >= 0 AND risk_score <= 100
    ),
    CONSTRAINT chk_compliance_review_invariants CHECK (
        (status = 'PENDING' AND decision IS NULL AND reviewer_id IS NULL AND decided_at IS NULL)
        OR
        (status IN ('CLEARED', 'REJECTED') 
         AND decision IS NOT NULL 
         AND reviewer_id IS NOT NULL 
         AND char_length(btrim(reviewer_id)) >= 1
         AND justification IS NOT NULL 
         AND char_length(btrim(justification)) >= 3 
         AND decided_at IS NOT NULL)
    ),
    CONSTRAINT chk_compliance_review_reviewer_length CHECK (
        reviewer_id IS NULL OR (char_length(reviewer_id) + regexp_count(reviewer_id, '[\U00010000-\U0010FFFF]')) <= 100
    ),
    CONSTRAINT chk_compliance_review_justification_length CHECK (
        justification IS NULL OR (char_length(justification) + regexp_count(justification, '[\U00010000-\U0010FFFF]')) <= 1000
    ),
    CONSTRAINT chk_compliance_review_timestamps CHECK (
        updated_at >= created_at
        AND (decided_at IS NULL OR decided_at >= created_at)
    )
);

CREATE INDEX idx_compliance_review_status 
    ON compliance.compliance_review (status);

CREATE INDEX idx_compliance_review_created_at 
    ON compliance.compliance_review (created_at);
