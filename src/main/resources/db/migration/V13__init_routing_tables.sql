-- V13__init_routing_tables.sql
-- CorrespondentRoute aggregate table, priority uniqueness, UTF-16 length alignment, and integrity constraints (Phase 6)

CREATE TABLE routing.correspondent_route (
    route_id                    UUID                        NOT NULL,
    version                     BIGINT                      NOT NULL DEFAULT 0,
    route_reference             VARCHAR(100)                NOT NULL,
    destination_bic             VARCHAR(11)                 NOT NULL,
    correspondent_bic           VARCHAR(11)                 NOT NULL,
    currency                    VARCHAR(3)                  NOT NULL,
    clearing_account_reference  VARCHAR(100)                NOT NULL,
    priority                    INTEGER                     NOT NULL,
    status                      VARCHAR(16)                 NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE    NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE    NOT NULL,

    CONSTRAINT pk_correspondent_route PRIMARY KEY (route_id),
    CONSTRAINT uk_correspondent_route_reference UNIQUE (route_reference),
    CONSTRAINT uk_correspondent_route_dest_ccy_priority UNIQUE (destination_bic, currency, priority),

    CONSTRAINT chk_correspondent_route_version_non_negative CHECK (
        version >= 0
    ),
    CONSTRAINT chk_correspondent_route_reference_format CHECK (
        char_length(btrim(route_reference)) >= 1
        AND (char_length(route_reference) + regexp_count(route_reference, '[\U00010000-\U0010FFFF]')) <= 100
        AND route_reference = btrim(route_reference)
        AND route_reference ~ '^[A-Za-z0-9:_.-]{1,100}$'
    ),
    CONSTRAINT chk_correspondent_route_destination_bic CHECK (
        destination_bic ~ '^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$'
    ),
    CONSTRAINT chk_correspondent_route_correspondent_bic CHECK (
        correspondent_bic ~ '^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$'
    ),
    CONSTRAINT chk_correspondent_route_currency_supported CHECK (
        currency IN ('USD', 'EUR', 'GBP', 'INR', 'JPY', 'CHF', 'SGD', 'AED')
    ),
    CONSTRAINT chk_correspondent_route_clearing_account CHECK (
        char_length(btrim(clearing_account_reference)) >= 1
        AND (char_length(clearing_account_reference) + regexp_count(clearing_account_reference, '[\U00010000-\U0010FFFF]')) <= 100
        AND clearing_account_reference = btrim(clearing_account_reference)
        AND clearing_account_reference !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    CONSTRAINT chk_correspondent_route_priority_bounds CHECK (
        priority BETWEEN 1 AND 1000
    ),
    CONSTRAINT chk_correspondent_route_status_valid CHECK (
        status IN ('ACTIVE', 'INACTIVE')
    ),
    CONSTRAINT chk_correspondent_route_timestamps CHECK (
        updated_at >= created_at
    )
);

CREATE INDEX idx_correspondent_route_lookup
    ON routing.correspondent_route (destination_bic, currency, status, priority ASC, route_reference ASC);
