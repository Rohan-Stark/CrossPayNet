-- V8__init_payment_tables.sql
-- Payment aggregate table, state-invariants, and integrity constraints (ADR-006)

CREATE TABLE payment.payment (
    payment_id                  UUID                        NOT NULL,
    version                     BIGINT                      NOT NULL DEFAULT 0,
    source_account_id           UUID                        NOT NULL,
    payment_reference           VARCHAR(255)                NOT NULL,
    instruction_fingerprint     CHAR(64)                    NOT NULL,
    amount                      NUMERIC                     NOT NULL,
    currency                    VARCHAR(3)                  NOT NULL,
    beneficiary_name            VARCHAR(140)                NOT NULL,
    beneficiary_account_number  VARCHAR(34)                 NOT NULL,
    beneficiary_bank_bic        VARCHAR(11)                 NOT NULL,
    status                      VARCHAR(30)                 NOT NULL,
    reservation_token           UUID                        NULL,
    route_reference             VARCHAR(100)                NULL,
    external_dispatch_reference VARCHAR(100)                NULL,
    ledger_transaction_id       UUID                        NULL,
    status_reason_code          VARCHAR(40)                 NULL,
    status_reason_detail        VARCHAR(500)                NULL,
    created_at                  TIMESTAMP WITH TIME ZONE    NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE    NOT NULL,

    CONSTRAINT pk_payment PRIMARY KEY (payment_id),
    CONSTRAINT uk_payment_source_account_ref UNIQUE (source_account_id, payment_reference),

    CONSTRAINT chk_payment_version_non_negative CHECK (
        version >= 0
    ),
    CONSTRAINT chk_payment_reference_non_blank CHECK (
        char_length(btrim(payment_reference)) BETWEEN 1 AND 255
        AND payment_reference = btrim(payment_reference)
        AND payment_reference !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_payment_instruction_fingerprint_format CHECK (
        instruction_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_payment_amount CHECK (
        amount > 0
        AND amount <= 999999999999999.9999
        AND scale(trim_scale(amount)) <= 4
    ),
    CONSTRAINT chk_payment_currency_supported CHECK (
        currency IN ('USD', 'EUR', 'GBP', 'INR', 'JPY', 'CHF', 'SGD', 'AED')
    ),
    CONSTRAINT chk_payment_beneficiary_name CHECK (
        char_length(btrim(beneficiary_name)) BETWEEN 1 AND 140
        AND beneficiary_name = btrim(beneficiary_name)
        AND beneficiary_name !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_payment_beneficiary_account CHECK (
        char_length(btrim(beneficiary_account_number)) BETWEEN 1 AND 34
        AND beneficiary_account_number = btrim(beneficiary_account_number)
        AND beneficiary_account_number !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_payment_beneficiary_bic CHECK (
        beneficiary_bank_bic ~ '^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$'
    ),
    CONSTRAINT chk_payment_status_valid CHECK (
        status IN (
            'VALIDATING',
            'COMPLIANCE_HOLD',
            'ROUTING',
            'EXECUTING',
            'PENDING_EXTERNAL',
            'SETTLED',
            'REJECTED',
            'CANCELLED',
            'FAILED'
        )
    ),
    CONSTRAINT chk_payment_reason_code_valid CHECK (
        status_reason_code IS NULL
        OR status_reason_code IN (
            'INSUFFICIENT_FUNDS',
            'INVALID_ACCOUNT_STATE',
            'CURRENCY_MISMATCH',
            'COMPLIANCE_REJECTED',
            'NO_ROUTE_AVAILABLE',
            'CUSTOMER_CANCELLED',
            'EXTERNAL_NETWORK_REJECTED',
            'OPERATIONAL_FAILURE'
        )
    ),
    CONSTRAINT chk_payment_route_reference CHECK (
        route_reference IS NULL
        OR (
            char_length(btrim(route_reference)) BETWEEN 1 AND 100
            AND route_reference = btrim(route_reference)
            AND route_reference !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    CONSTRAINT chk_payment_external_dispatch_reference CHECK (
        external_dispatch_reference IS NULL
        OR (
            char_length(btrim(external_dispatch_reference)) BETWEEN 1 AND 100
            AND external_dispatch_reference = btrim(external_dispatch_reference)
            AND external_dispatch_reference !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    CONSTRAINT chk_payment_reason_detail CHECK (
        status_reason_detail IS NULL
        OR (
            status_reason_code IS NOT NULL
            AND char_length(btrim(status_reason_detail)) BETWEEN 1 AND 500
            AND status_reason_detail = btrim(status_reason_detail)
            AND status_reason_detail !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    CONSTRAINT chk_payment_timestamps CHECK (
        updated_at >= created_at
    ),
    CONSTRAINT chk_payment_state_invariants CHECK (
        (
            status = 'VALIDATING'
            AND reservation_token IS NULL
            AND route_reference IS NULL
            AND external_dispatch_reference IS NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NULL
            AND status_reason_detail IS NULL
        )
        OR (
            status IN ('COMPLIANCE_HOLD', 'ROUTING')
            AND reservation_token IS NOT NULL
            AND route_reference IS NULL
            AND external_dispatch_reference IS NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NULL
            AND status_reason_detail IS NULL
        )
        OR (
            status = 'EXECUTING'
            AND reservation_token IS NOT NULL
            AND route_reference IS NOT NULL
            AND external_dispatch_reference IS NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NULL
            AND status_reason_detail IS NULL
        )
        OR (
            status = 'PENDING_EXTERNAL'
            AND reservation_token IS NOT NULL
            AND route_reference IS NOT NULL
            AND external_dispatch_reference IS NOT NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NULL
            AND status_reason_detail IS NULL
        )
        OR (
            status = 'SETTLED'
            AND reservation_token IS NOT NULL
            AND route_reference IS NOT NULL
            AND external_dispatch_reference IS NOT NULL
            AND ledger_transaction_id IS NOT NULL
            AND status_reason_code IS NULL
            AND status_reason_detail IS NULL
        )
        OR (
            status = 'REJECTED'
            AND route_reference IS NULL
            AND external_dispatch_reference IS NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NOT NULL
            AND (
                (
                    reservation_token IS NULL
                    AND status_reason_code IN ('INSUFFICIENT_FUNDS', 'INVALID_ACCOUNT_STATE', 'CURRENCY_MISMATCH')
                )
                OR (
                    reservation_token IS NOT NULL
                    AND status_reason_code IN ('COMPLIANCE_REJECTED', 'NO_ROUTE_AVAILABLE')
                )
            )
        )
        OR (
            status = 'CANCELLED'
            AND route_reference IS NULL
            AND external_dispatch_reference IS NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NOT NULL
            AND status_reason_code = 'CUSTOMER_CANCELLED'
        )
        OR (
            status = 'FAILED'
            AND reservation_token IS NOT NULL
            AND route_reference IS NOT NULL
            AND ledger_transaction_id IS NULL
            AND status_reason_code IS NOT NULL
            AND status_reason_code IN ('EXTERNAL_NETWORK_REJECTED', 'OPERATIONAL_FAILURE')
        )
    )
);

CREATE INDEX idx_payment_status_created_at
    ON payment.payment (status, created_at);

CREATE INDEX idx_payment_source_account_created_at
    ON payment.payment (source_account_id, created_at DESC);
