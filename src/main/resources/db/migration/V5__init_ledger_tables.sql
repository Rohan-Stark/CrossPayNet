-- V5__init_ledger_tables.sql
-- Immutable double-entry financial ledger tables, constraints, and indexes (ADR-006)

CREATE TABLE ledger.ledger_transaction (
    transaction_id          UUID                        NOT NULL,
    posting_key             VARCHAR(200)                NOT NULL,
    transaction_type        VARCHAR(20)                 NOT NULL,
    currency                VARCHAR(3)                  NOT NULL,
    total_amount            NUMERIC                     NOT NULL,
    entry_count             INT                         NOT NULL,
    external_reference      VARCHAR(255)                NOT NULL,
    description             VARCHAR(500)                NULL,
    reversed_transaction_id UUID                        NULL,
    request_fingerprint     CHAR(64)                    NOT NULL,
    posted_at               TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ledger_transaction PRIMARY KEY (transaction_id),
    CONSTRAINT uk_ledger_transaction_posting_key UNIQUE (posting_key),
    CONSTRAINT uk_ledger_tx_reversed_tx UNIQUE (reversed_transaction_id),
    CONSTRAINT fk_ledger_tx_reversed_tx FOREIGN KEY (reversed_transaction_id)
        REFERENCES ledger.ledger_transaction (transaction_id),

    CONSTRAINT chk_ledger_tx_posting_key_format CHECK (
        posting_key ~ '^[A-Za-z0-9:_.-]{1,200}$'
    ),
    CONSTRAINT chk_ledger_tx_type CHECK (
        transaction_type IN ('STANDARD', 'REVERSAL')
    ),
    CONSTRAINT chk_ledger_tx_currency CHECK (
        currency IN ('USD', 'EUR', 'GBP', 'INR', 'JPY', 'CHF', 'SGD', 'AED')
    ),
    CONSTRAINT chk_ledger_tx_total_amount CHECK (
        total_amount > 0
        AND total_amount <= 999999999999999.9999
        AND scale(trim_scale(total_amount)) <= 4
    ),
    CONSTRAINT chk_ledger_tx_entry_count CHECK (
        entry_count BETWEEN 2 AND 100
    ),
    CONSTRAINT chk_ledger_tx_external_ref CHECK (
        char_length(external_reference) >= 1
        AND (char_length(external_reference) + regexp_count(external_reference, '[\U00010000-\U0010FFFF]')) <= 255
        AND external_reference !~ '(^[\u0001-\u0020]|[\u0001-\u0020]$)'
    ),
    CONSTRAINT chk_ledger_tx_description CHECK (
        description IS NULL
        OR (
            char_length(description) >= 1
            AND (char_length(description) + regexp_count(description, '[\U00010000-\U0010FFFF]')) <= 500
            AND description !~ '(^[\u0001-\u0020]|[\u0001-\u0020]$)'
        )
    ),
    CONSTRAINT chk_ledger_tx_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_ledger_tx_reversal_consistency CHECK (
        (
            transaction_type = 'STANDARD'
            AND reversed_transaction_id IS NULL
            AND posting_key !~* '^REVERSAL:'
        )
        OR (
            transaction_type = 'REVERSAL'
            AND reversed_transaction_id IS NOT NULL
            AND reversed_transaction_id <> transaction_id
            AND posting_key = ('REVERSAL:' || reversed_transaction_id::text)
        )
    )
);

CREATE TABLE ledger.ledger_entry (
    entry_id          UUID            NOT NULL,
    transaction_id    UUID            NOT NULL,
    line_number       INT             NOT NULL,
    account_type      VARCHAR(30)     NOT NULL,
    account_reference VARCHAR(100)    NOT NULL,
    direction         VARCHAR(10)     NOT NULL,
    currency          VARCHAR(3)      NOT NULL,
    amount            NUMERIC         NOT NULL,

    CONSTRAINT pk_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT fk_ledger_entry_transaction FOREIGN KEY (transaction_id)
        REFERENCES ledger.ledger_transaction (transaction_id),
    CONSTRAINT uk_ledger_entry_tx_line UNIQUE (transaction_id, line_number),

    CONSTRAINT chk_ledger_entry_line_number CHECK (
        line_number BETWEEN 1 AND 100
    ),
    CONSTRAINT chk_ledger_entry_account_type CHECK (
        account_type IN ('BANKING_ACCOUNT', 'INTERNAL')
    ),
    CONSTRAINT chk_ledger_entry_account_ref CHECK (
        char_length(account_reference) BETWEEN 1 AND 100
        AND account_reference !~ '(^[\u0001-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]|[\u0001-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]$)'
        AND account_reference !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_ledger_entry_direction CHECK (
        direction IN ('DEBIT', 'CREDIT')
    ),
    CONSTRAINT chk_ledger_entry_currency CHECK (
        currency IN ('USD', 'EUR', 'GBP', 'INR', 'JPY', 'CHF', 'SGD', 'AED')
    ),
    CONSTRAINT chk_ledger_entry_amount CHECK (
        amount > 0
        AND amount <= 999999999999999.9999
        AND scale(trim_scale(amount)) <= 4
    )
);

-- Note: uk_ledger_entry_tx_line UNIQUE (transaction_id, line_number) provides the
-- B-tree index with leading column transaction_id for parent-to-entry joins and ordered
-- line_number scans.
CREATE INDEX idx_ledger_entry_account_currency
    ON ledger.ledger_entry (account_type, account_reference, currency);
