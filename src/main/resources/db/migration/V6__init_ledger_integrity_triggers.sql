-- V6__init_ledger_integrity_triggers.sql
-- Append-only immutability triggers and deferred double-entry/reversal constraint triggers (ADR-006)

CREATE OR REPLACE FUNCTION ledger.enforce_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Ledger tables are strictly append-only: % on %.% is prohibited',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'P0001';
END;
$$;

CREATE TRIGGER trg_ledger_tx_immutable_row
    BEFORE UPDATE OR DELETE ON ledger.ledger_transaction
    FOR EACH ROW
    EXECUTE FUNCTION ledger.enforce_append_only();

CREATE TRIGGER trg_ledger_tx_immutable_stmt
    BEFORE TRUNCATE ON ledger.ledger_transaction
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.enforce_append_only();

CREATE TRIGGER trg_ledger_entry_immutable_row
    BEFORE UPDATE OR DELETE ON ledger.ledger_entry
    FOR EACH ROW
    EXECUTE FUNCTION ledger.enforce_append_only();

CREATE TRIGGER trg_ledger_entry_immutable_stmt
    BEFORE TRUNCATE ON ledger.ledger_entry
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.enforce_append_only();

CREATE OR REPLACE FUNCTION ledger.verify_transaction_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_tx_id                   UUID := NEW.transaction_id;
    v_tx                      ledger.ledger_transaction%ROWTYPE;
    v_orig_tx                 ledger.ledger_transaction%ROWTYPE;
    v_actual_count            INT;
    v_min_line                INT;
    v_max_line                INT;
    v_currency_mismatch_count INT;
    v_debit_count             INT;
    v_credit_count            INT;
    v_debit_sum               NUMERIC;
    v_credit_sum              NUMERIC;
    v_multiset_diff_count     INT;
BEGIN
    SELECT *
      INTO v_tx
      FROM ledger.ledger_transaction
     WHERE transaction_id = v_tx_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Ledger integrity violation: parent transaction % not found', v_tx_id
            USING ERRCODE = 'P0001';
    END IF;

    SELECT COUNT(*),
           MIN(line_number),
           MAX(line_number),
           COUNT(*) FILTER (WHERE currency <> v_tx.currency),
           COUNT(*) FILTER (WHERE direction = 'DEBIT'),
           COUNT(*) FILTER (WHERE direction = 'CREDIT'),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO v_actual_count,
           v_min_line,
           v_max_line,
           v_currency_mismatch_count,
           v_debit_count,
           v_credit_count,
           v_debit_sum,
           v_credit_sum
      FROM ledger.ledger_entry
     WHERE transaction_id = v_tx_id;

    IF v_actual_count < 2 OR v_actual_count <> v_tx.entry_count THEN
        RAISE EXCEPTION 'Ledger integrity violation: transaction % entry count mismatch (actual %, expected %)',
            v_tx_id, v_actual_count, v_tx.entry_count
            USING ERRCODE = 'P0001';
    END IF;

    IF v_min_line <> 1 OR v_max_line <> v_tx.entry_count THEN
        RAISE EXCEPTION 'Ledger integrity violation: transaction % entries do not have contiguous line numbers 1..% (min %, max %)',
            v_tx_id, v_tx.entry_count, v_min_line, v_max_line
            USING ERRCODE = 'P0001';
    END IF;

    IF v_currency_mismatch_count > 0 THEN
        RAISE EXCEPTION 'Ledger integrity violation: transaction % has % entry(ies) not matching transaction currency %',
            v_tx_id, v_currency_mismatch_count, v_tx.currency
            USING ERRCODE = 'P0001';
    END IF;

    IF v_debit_count < 1 OR v_credit_count < 1 THEN
        RAISE EXCEPTION 'Ledger integrity violation: transaction % must have at least one DEBIT and one CREDIT entry (debits %, credits %)',
            v_tx_id, v_debit_count, v_credit_count
            USING ERRCODE = 'P0001';
    END IF;

    IF v_debit_sum <> v_credit_sum OR v_debit_sum <> v_tx.total_amount THEN
        RAISE EXCEPTION 'Ledger integrity violation: transaction % is unbalanced or does not match header total_amount (debit sum %, credit sum %, header total_amount %)',
            v_tx_id, v_debit_sum, v_credit_sum, v_tx.total_amount
            USING ERRCODE = 'P0001';
    END IF;

    IF v_tx.transaction_type = 'REVERSAL' THEN
        SELECT *
          INTO v_orig_tx
          FROM ledger.ledger_transaction
         WHERE transaction_id = v_tx.reversed_transaction_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Ledger integrity violation: reversal transaction % references non-existent original transaction %',
                v_tx_id, v_tx.reversed_transaction_id
                USING ERRCODE = 'P0001';
        END IF;

        IF v_orig_tx.transaction_type <> 'STANDARD' OR v_orig_tx.reversed_transaction_id IS NOT NULL THEN
            RAISE EXCEPTION 'Ledger integrity violation: reversal transaction % target % is not a STANDARD transaction (type %)',
                v_tx_id, v_tx.reversed_transaction_id, v_orig_tx.transaction_type
                USING ERRCODE = 'P0001';
        END IF;

        IF v_tx.currency <> v_orig_tx.currency
           OR v_tx.total_amount <> v_orig_tx.total_amount
           OR v_tx.entry_count <> v_orig_tx.entry_count THEN
            RAISE EXCEPTION 'Ledger integrity violation: reversal transaction % header (currency %, total_amount %, entry_count %) does not match original transaction % (currency %, total_amount %, entry_count %)',
                v_tx_id, v_tx.currency, v_tx.total_amount, v_tx.entry_count,
                v_tx.reversed_transaction_id, v_orig_tx.currency, v_orig_tx.total_amount, v_orig_tx.entry_count
                USING ERRCODE = 'P0001';
        END IF;

        SELECT COUNT(*)
          INTO v_multiset_diff_count
          FROM (
              SELECT account_type,
                     account_reference,
                     currency,
                     amount,
                     CASE WHEN direction = 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END AS expected_direction,
                     COUNT(*) AS tuple_count
                FROM ledger.ledger_entry
               WHERE transaction_id = v_tx.reversed_transaction_id
               GROUP BY account_type, account_reference, currency, amount,
                        CASE WHEN direction = 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
          ) orig
          FULL OUTER JOIN (
              SELECT account_type,
                     account_reference,
                     currency,
                     amount,
                     direction,
                     COUNT(*) AS tuple_count
                FROM ledger.ledger_entry
               WHERE transaction_id = v_tx_id
               GROUP BY account_type, account_reference, currency, amount, direction
          ) rev
            ON orig.account_type = rev.account_type
           AND orig.account_reference = rev.account_reference
           AND orig.currency = rev.currency
           AND orig.amount = rev.amount
           AND orig.expected_direction = rev.direction
         WHERE orig.tuple_count IS DISTINCT FROM rev.tuple_count;

        IF v_multiset_diff_count > 0 THEN
            RAISE EXCEPTION 'Ledger integrity violation: reversal transaction % entries do not form a 1-to-1 inverted bijection with original transaction %',
                v_tx_id, v_tx.reversed_transaction_id
                USING ERRCODE = 'P0001';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_verify_ledger_tx_integrity
    AFTER INSERT ON ledger.ledger_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.verify_transaction_integrity();

CREATE CONSTRAINT TRIGGER trg_verify_ledger_entry_integrity
    AFTER INSERT ON ledger.ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.verify_transaction_integrity();
