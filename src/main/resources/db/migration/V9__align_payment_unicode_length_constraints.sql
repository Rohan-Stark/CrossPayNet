-- V9__align_payment_unicode_length_constraints.sql
-- Align PostgreSQL CHECK constraints on payment.payment with Java UTF-16 String.length()
-- so supplementary Unicode characters (U+10000..U+10FFFF, 2 UTF-16 code units each)
-- cannot be inserted via direct SQL when they would exceed Java value-object length limits.

ALTER TABLE payment.payment
    DROP CONSTRAINT chk_payment_reference_non_blank,
    DROP CONSTRAINT chk_payment_beneficiary_name,
    DROP CONSTRAINT chk_payment_beneficiary_account,
    DROP CONSTRAINT chk_payment_route_reference,
    DROP CONSTRAINT chk_payment_external_dispatch_reference,
    DROP CONSTRAINT chk_payment_reason_detail,

    ADD CONSTRAINT chk_payment_reference_non_blank CHECK (
        char_length(btrim(payment_reference)) >= 1
        AND (char_length(payment_reference) + regexp_count(payment_reference, '[\U00010000-\U0010FFFF]')) <= 255
        AND payment_reference = btrim(payment_reference)
        AND payment_reference !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    ADD CONSTRAINT chk_payment_beneficiary_name CHECK (
        char_length(btrim(beneficiary_name)) >= 1
        AND (char_length(beneficiary_name) + regexp_count(beneficiary_name, '[\U00010000-\U0010FFFF]')) <= 140
        AND beneficiary_name = btrim(beneficiary_name)
        AND beneficiary_name !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    ADD CONSTRAINT chk_payment_beneficiary_account CHECK (
        char_length(btrim(beneficiary_account_number)) >= 1
        AND (char_length(beneficiary_account_number) + regexp_count(beneficiary_account_number, '[\U00010000-\U0010FFFF]')) <= 34
        AND beneficiary_account_number = btrim(beneficiary_account_number)
        AND beneficiary_account_number !~ '[\u0001-\u001F\u007F-\u009F]'
    ),
    ADD CONSTRAINT chk_payment_route_reference CHECK (
        route_reference IS NULL
        OR (
            char_length(btrim(route_reference)) >= 1
            AND (char_length(route_reference) + regexp_count(route_reference, '[\U00010000-\U0010FFFF]')) <= 100
            AND route_reference = btrim(route_reference)
            AND route_reference !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    ADD CONSTRAINT chk_payment_external_dispatch_reference CHECK (
        external_dispatch_reference IS NULL
        OR (
            char_length(btrim(external_dispatch_reference)) >= 1
            AND (char_length(external_dispatch_reference) + regexp_count(external_dispatch_reference, '[\U00010000-\U0010FFFF]')) <= 100
            AND external_dispatch_reference = btrim(external_dispatch_reference)
            AND external_dispatch_reference !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    ),
    ADD CONSTRAINT chk_payment_reason_detail CHECK (
        status_reason_detail IS NULL
        OR (
            status_reason_code IS NOT NULL
            AND char_length(btrim(status_reason_detail)) >= 1
            AND (char_length(status_reason_detail) + regexp_count(status_reason_detail, '[\U00010000-\U0010FFFF]')) <= 500
            AND status_reason_detail = btrim(status_reason_detail)
            AND status_reason_detail !~ '[\u0001-\u001F\u007F-\u009F]'
        )
    );
