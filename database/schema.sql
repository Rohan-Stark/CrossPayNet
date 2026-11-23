-- ============================================================
-- CrossPayNet: Relational Database Management System (DBMS) Schema
-- Academic / College-Level Demonstration Project
-- Database Engine: SQLite3 (Standard Relational DBMS)
-- ============================================================

-- Enable foreign key constraint enforcement
PRAGMA foreign_keys = ON;

-- ------------------------------------------------------------
-- 1. CUSTOMERS TABLE
-- Stores profile information for account holders
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    customer_id INTEGER PRIMARY KEY AUTOINCREMENT,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    country VARCHAR(3) NOT NULL, -- ISO-3166 3-letter country code
    kyc_status VARCHAR(20) DEFAULT 'VERIFIED' CHECK (kyc_status IN ('VERIFIED', 'PENDING', 'REJECTED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ------------------------------------------------------------
-- 2. ACCOUNTS TABLE
-- Customer operational bank accounts supporting multi-currency
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounts (
    account_id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id INTEGER NOT NULL,
    account_number VARCHAR(34) UNIQUE NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP', 'INR', 'JPY', 'SGD', 'AED')),
    balance DECIMAL(15, 2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0.00),
    reserved_amount DECIMAL(15, 2) NOT NULL DEFAULT 0.00 CHECK (reserved_amount >= 0.00),
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers (customer_id) ON DELETE RESTRICT
);

-- ------------------------------------------------------------
-- 3. FUND RESERVATIONS TABLE
-- Manages hold on funds before international settlement
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fund_reservations (
    reservation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    amount DECIMAL(15, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED')),
    reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts (account_id) ON DELETE CASCADE
);

-- ------------------------------------------------------------
-- 4. CORRESPONDENT ROUTES TABLE (Routing Module)
-- Directory of partner banks and SWIFT/BIC clearing corridors
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS correspondent_routes (
    route_id INTEGER PRIMARY KEY AUTOINCREMENT,
    destination_bic VARCHAR(11) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    country VARCHAR(50) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    fee DECIMAL(10, 2) DEFAULT 15.00,
    is_active INTEGER DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ------------------------------------------------------------
-- 5. PAYMENTS TABLE (Core Payment Workflow Machine)
-- Tracks the 6-state payment lifecycle:
-- PENDING -> SCREENING -> ROUTED -> SETTLED | REJECTED | CANCELLED
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payments (
    payment_id INTEGER PRIMARY KEY AUTOINCREMENT,
    reference_no VARCHAR(50) UNIQUE NOT NULL,
    source_account_id INTEGER NOT NULL,
    amount DECIMAL(15, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    beneficiary_name VARCHAR(100) NOT NULL,
    beneficiary_account VARCHAR(34) NOT NULL,
    beneficiary_bic VARCHAR(11) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SCREENING', 'ROUTED', 'SETTLED', 'REJECTED', 'CANCELLED')),
    status_reason TEXT,
    route_id INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMP,
    FOREIGN KEY (source_account_id) REFERENCES accounts (account_id) ON DELETE RESTRICT,
    FOREIGN KEY (route_id) REFERENCES correspondent_routes (route_id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 6. COMPLIANCE REVIEWS TABLE (Sanctions & AML Screening)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS compliance_reviews (
    review_id INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_id INTEGER NOT NULL,
    status VARCHAR(20) DEFAULT 'PASSED' CHECK (status IN ('PASSED', 'FLAGGED', 'REJECTED')),
    matched_rule VARCHAR(100),
    review_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (payment_id) REFERENCES payments (payment_id) ON DELETE CASCADE
);

-- ------------------------------------------------------------
-- 7. DOUBLE-ENTRY LEDGER: TRANSACTIONS & JOURNAL ENTRIES
-- Guaranteeing that every payment debit matches a credit line
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger_transactions (
    transaction_id INTEGER PRIMARY KEY AUTOINCREMENT,
    reference VARCHAR(100) UNIQUE NOT NULL,
    transaction_type VARCHAR(30) NOT NULL CHECK (transaction_type IN ('PAYMENT_SETTLEMENT', 'ACCOUNT_DEPOSIT', 'FEE_CHARGE', 'REVERSAL')),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id INTEGER NOT NULL,
    account_identifier VARCHAR(50) NOT NULL,
    entry_type VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount DECIMAL(15, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (transaction_id) ON DELETE CASCADE
);

-- ------------------------------------------------------------
-- 8. PAYMENT MESSAGES (Financial Messaging: pacs.008 simulation)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_messages (
    message_id INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_id INTEGER NOT NULL,
    message_type VARCHAR(20) DEFAULT 'pacs.008.001.08',
    sender_bic VARCHAR(11) NOT NULL,
    receiver_bic VARCHAR(11) NOT NULL,
    payload_summary TEXT NOT NULL,
    digest_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (payment_id) REFERENCES payments (payment_id) ON DELETE CASCADE
);

-- ============================================================
-- DATABASE INDEXES (DBMS Optimization Feature)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_accounts_customer ON accounts (customer_id);
CREATE INDEX IF NOT EXISTS idx_accounts_accnum ON accounts (account_number);
CREATE INDEX IF NOT EXISTS idx_payments_source ON payments (source_account_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments (status);
CREATE INDEX IF NOT EXISTS idx_payments_ref ON payments (reference_no);
CREATE INDEX IF NOT EXISTS idx_routes_bic ON correspondent_routes (destination_bic, currency);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_tx ON ledger_entries (transaction_id);

-- ============================================================
-- DATABASE TRIGGERS (DBMS Integrity Feature)
-- ============================================================

-- Trigger 1: Automatically check that account has sufficient available balance when reserving funds
CREATE TRIGGER IF NOT EXISTS trg_validate_fund_reservation
BEFORE INSERT ON fund_reservations
BEGIN
    SELECT
        CASE
            WHEN ((SELECT balance - reserved_amount FROM accounts WHERE account_id = NEW.account_id) < NEW.amount)
            THEN RAISE(ABORT, 'Insufficient available balance to reserve funds.')
        END;
END;

-- Trigger 2: Automatically increase reserved_amount in accounts upon new active reservation
CREATE TRIGGER IF NOT EXISTS trg_increment_reserved_amount
AFTER INSERT ON fund_reservations
WHEN NEW.status = 'ACTIVE'
BEGIN
    UPDATE accounts
    SET reserved_amount = reserved_amount + NEW.amount
    WHERE account_id = NEW.account_id;
END;

-- Trigger 3: Release or consume reserved amount when reservation status changes
CREATE TRIGGER IF NOT EXISTS trg_update_reservation_status
AFTER UPDATE OF status ON fund_reservations
BEGIN
    -- If released, release the hold back to available
    UPDATE accounts
    SET reserved_amount = reserved_amount - OLD.amount
    WHERE account_id = NEW.account_id AND OLD.status = 'ACTIVE' AND NEW.status IN ('RELEASED', 'CONSUMED');
END;

-- ============================================================
-- DATABASE VIEWS (DBMS Reporting Feature)
-- ============================================================

-- View 1: Customer Account Overview with Computed Available Balance
CREATE VIEW IF NOT EXISTS view_account_summary AS
SELECT
    a.account_id,
    c.full_name AS customer_name,
    c.country AS customer_country,
    a.account_number,
    a.currency,
    a.balance AS total_balance,
    a.reserved_amount,
    (a.balance - a.reserved_amount) AS available_balance,
    a.status AS account_status,
    a.created_at
FROM accounts a
JOIN customers c ON a.customer_id = c.customer_id;

-- View 2: Complete Payment Lifecycle History
CREATE VIEW IF NOT EXISTS view_payment_details AS
SELECT
    p.payment_id,
    p.reference_no,
    c.full_name AS sender_name,
    a.account_number AS sender_account,
    p.amount,
    p.currency,
    p.beneficiary_name,
    p.beneficiary_account,
    p.beneficiary_bic,
    r.bank_name AS destination_bank,
    r.country AS destination_country,
    p.status,
    p.status_reason,
    p.created_at,
    p.settled_at
FROM payments p
JOIN accounts a ON p.source_account_id = a.account_id
JOIN customers c ON a.customer_id = c.customer_id
LEFT JOIN correspondent_routes r ON p.route_id = r.route_id;

-- View 3: Double-Entry Trial Balance Verification (Total Debits vs Credits)
CREATE VIEW IF NOT EXISTS view_trial_balance AS
SELECT
    currency,
    SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) AS total_debits,
    SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS total_credits,
    (SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) - 
     SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END)) AS balance_difference
FROM ledger_entries
GROUP BY currency;
