-- V11__init_messaging_tables.sql
-- PaymentMessage aggregate table, state-invariants, UTF-16 length alignment, and integrity constraints (Phase 5)

CREATE TABLE messaging.payment_message (
    message_id                  UUID                        NOT NULL,
    version                     BIGINT                      NOT NULL DEFAULT 0,
    payment_id                  UUID                        NOT NULL,
    attempt_number              INTEGER                     NOT NULL,
    idempotency_key             VARCHAR(200)                NOT NULL,
    message_type                VARCHAR(35)                 NOT NULL,
    sender_bic                  VARCHAR(11)                 NOT NULL,
    receiver_bic                VARCHAR(11)                 NOT NULL,
    route_reference             VARCHAR(100)                NOT NULL,
    amount                      NUMERIC                     NOT NULL,
    currency                    VARCHAR(3)                  NOT NULL,
    beneficiary_name            VARCHAR(140)                NOT NULL,
    beneficiary_account_number  VARCHAR(34)                 NOT NULL,
    payload                     TEXT                        NOT NULL,
    payload_digest              CHAR(64)                    NOT NULL,
    status                      VARCHAR(32)                 NOT NULL,
    acknowledgment_reference    VARCHAR(100)                NULL,
    peer_rejection_code         VARCHAR(35)                 NULL,
    peer_rejection_reason       VARCHAR(500)                NULL,
    timeout_reason              VARCHAR(500)                NULL,
    created_at                  TIMESTAMP WITH TIME ZONE    NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE    NOT NULL,

    CONSTRAINT pk_payment_message PRIMARY KEY (message_id),
    CONSTRAINT uk_payment_message_attempt UNIQUE (payment_id, attempt_number),
    CONSTRAINT uk_payment_message_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT chk_payment_message_version_non_negative CHECK (
        version >= 0
    ),
    CONSTRAINT chk_payment_message_attempt_bounds CHECK (
        attempt_number BETWEEN 1 AND 100
    ),
    CONSTRAINT chk_payment_message_idempotency_key_format CHECK (
        char_length(btrim(idempotency_key)) >= 1
        AND (char_length(idempotency_key) + regexp_count(idempotency_key, '[\U00010000-\U0010FFFF]')) <= 200
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key ~ '^[A-Za-z0-9:_.-]{1,200}$'
    ),
    CONSTRAINT chk_payment_message_type_supported CHECK (
        message_type = 'PACS_008'
    ),
    CONSTRAINT chk_payment_message_sender_bic CHECK (
        sender_bic ~ '^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$'
    ),
    CONSTRAINT chk_payment_message_receiver_bic CHECK (
        receiver_bic ~ '^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$'
    ),
    CONSTRAINT chk_payment_message_route_reference CHECK (
        char_length(btrim(route_reference)) >= 1
        AND (char_length(route_reference) + regexp_count(route_reference, '[\U00010000-\U0010FFFF]')) <= 100
        AND route_reference = btrim(route_reference)
        AND route_reference !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_payment_message_amount CHECK (
        amount > 0
        AND amount <= 999999999999999.9999
        AND scale(trim_scale(amount)) <= 4
    ),
    CONSTRAINT chk_payment_message_currency_supported CHECK (
        currency IN ('USD', 'EUR', 'GBP', 'INR', 'JPY', 'CHF', 'SGD', 'AED')
    ),
    CONSTRAINT chk_payment_message_beneficiary_name CHECK (
        char_length(btrim(beneficiary_name)) >= 1
        AND (char_length(beneficiary_name) + regexp_count(beneficiary_name, '[\U00010000-\U0010FFFF]')) <= 140
        AND beneficiary_name = btrim(beneficiary_name)
        AND beneficiary_name !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_payment_message_beneficiary_account CHECK (
        char_length(btrim(beneficiary_account_number)) >= 1
        AND (char_length(beneficiary_account_number) + regexp_count(beneficiary_account_number, '[\U00010000-\U0010FFFF]')) <= 34
        AND beneficiary_account_number = btrim(beneficiary_account_number)
        AND beneficiary_account_number !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_payment_message_payload_non_blank CHECK (
        char_length(btrim(payload)) >= 1
        AND payload = btrim(payload)
    ),
    CONSTRAINT chk_payment_message_payload_digest_format CHECK (
        payload_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_payment_message_status_valid CHECK (
        status IN (
            'GENERATED',
            'SIGNED',
            'SENT',
            'ACKNOWLEDGED',
            'NACKNOWLEDGED',
            'TIMEOUT'
        )
    ),
    CONSTRAINT chk_payment_message_acknowledgment_reference CHECK (
        acknowledgment_reference IS NULL
        OR (
            char_length(btrim(acknowledgment_reference)) >= 1
            AND (char_length(acknowledgment_reference) + regexp_count(acknowledgment_reference, '[\U00010000-\U0010FFFF]')) <= 100
            AND acknowledgment_reference = btrim(acknowledgment_reference)
            AND acknowledgment_reference !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    CONSTRAINT chk_payment_message_peer_rejection_code CHECK (
        peer_rejection_code IS NULL
        OR (
            char_length(btrim(peer_rejection_code)) >= 1
            AND (char_length(peer_rejection_code) + regexp_count(peer_rejection_code, '[\U00010000-\U0010FFFF]')) <= 35
            AND peer_rejection_code = btrim(peer_rejection_code)
            AND peer_rejection_code ~ '^[A-Za-z0-9_.-]{1,35}$'
        )
    ),
    CONSTRAINT chk_payment_message_peer_rejection_reason CHECK (
        peer_rejection_reason IS NULL
        OR (
            peer_rejection_code IS NOT NULL
            AND char_length(btrim(peer_rejection_reason)) >= 1
            AND (char_length(peer_rejection_reason) + regexp_count(peer_rejection_reason, '[\U00010000-\U0010FFFF]')) <= 500
            AND peer_rejection_reason = btrim(peer_rejection_reason)
            AND peer_rejection_reason !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    CONSTRAINT chk_payment_message_timeout_reason CHECK (
        timeout_reason IS NULL
        OR (
            char_length(btrim(timeout_reason)) >= 1
            AND (char_length(timeout_reason) + regexp_count(timeout_reason, '[\U00010000-\U0010FFFF]')) <= 500
            AND timeout_reason = btrim(timeout_reason)
            AND timeout_reason !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    CONSTRAINT chk_payment_message_timestamps CHECK (
        updated_at >= created_at
    ),
    CONSTRAINT chk_payment_message_state_invariants CHECK (
        (
            status IN ('GENERATED', 'SIGNED', 'SENT')
            AND acknowledgment_reference IS NULL
            AND peer_rejection_code IS NULL
            AND peer_rejection_reason IS NULL
            AND timeout_reason IS NULL
        )
        OR (
            status = 'ACKNOWLEDGED'
            AND acknowledgment_reference IS NOT NULL
            AND peer_rejection_code IS NULL
            AND peer_rejection_reason IS NULL
            AND timeout_reason IS NULL
        )
        OR (
            status = 'NACKNOWLEDGED'
            AND acknowledgment_reference IS NULL
            AND peer_rejection_code IS NOT NULL
            AND timeout_reason IS NULL
        )
        OR (
            status = 'TIMEOUT'
            AND acknowledgment_reference IS NULL
            AND peer_rejection_code IS NULL
            AND peer_rejection_reason IS NULL
        )
    )
);

CREATE INDEX idx_payment_message_payment_id_attempt
    ON messaging.payment_message (payment_id, attempt_number ASC);

CREATE INDEX idx_payment_message_status_created_at
    ON messaging.payment_message (status, created_at);
