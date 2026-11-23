-- ============================================================
-- CrossPayNet Rich Seed Data for Interactive Viva Demo
-- ============================================================

-- 1. Insert Initial Customers
INSERT OR IGNORE INTO customers (customer_id, full_name, email, country, kyc_status) VALUES
(1, 'Aditya Sharma', 'aditya.sharma@paycorp.in', 'IND', 'VERIFIED'),
(2, 'Sarah Jenkins', 's.jenkins@atlantictrade.com', 'USA', 'VERIFIED'),
(3, 'Hans Mueller', 'h.mueller@bavaria-tech.de', 'DEU', 'VERIFIED'),
(4, 'Elena Rostova', 'elena.rostova@londonfin.co.uk', 'GBR', 'VERIFIED'),
(5, 'Marcus Chen', 'marcus.chen@pacificlogistics.sg', 'SGP', 'VERIFIED'),
(6, 'Fatima Al-Mansoor', 'fatima.m@gulfcapital.ae', 'ARE', 'VERIFIED');

-- 2. Insert Accounts with Balances
INSERT OR IGNORE INTO accounts (account_id, customer_id, account_number, currency, balance, reserved_amount, status) VALUES
(1, 1, 'CPN-INR-10001', 'INR', 850000.00, 0.00, 'ACTIVE'),
(2, 1, 'CPN-USD-10002', 'USD', 42500.00, 0.00, 'ACTIVE'),
(3, 2, 'CPN-USD-20001', 'USD', 185000.00, 0.00, 'ACTIVE'),
(4, 3, 'CPN-EUR-30001', 'EUR', 94000.00, 0.00, 'ACTIVE'),
(5, 4, 'CPN-GBP-40001', 'GBP', 62000.00, 0.00, 'ACTIVE'),
(6, 5, 'CPN-SGD-50001', 'SGD', 115000.00, 0.00, 'ACTIVE'),
(7, 6, 'CPN-USD-60001', 'USD', 240000.00, 0.00, 'ACTIVE');

-- 3. Insert Correspondent Routes (BICs)
INSERT OR IGNORE INTO correspondent_routes (route_id, destination_bic, bank_name, country, currency, fee, is_active) VALUES
(1, 'CHASUS33XXX', 'JPMorgan Chase Bank, N.A.', 'United States', 'USD', 15.00, 1),
(2, 'DEUTDEDDFXX', 'Deutsche Bank AG', 'Germany', 'EUR', 12.00, 1),
(3, 'BARCGB22XXX', 'Barclays Bank UK PLC', 'United Kingdom', 'GBP', 10.00, 1),
(4, 'SBININBBXXX', 'State Bank of India', 'India', 'INR', 250.00, 1),
(5, 'SMBCJPJTXXX', 'Sumitomo Mitsui Banking Corp', 'Japan', 'JPY', 1500.00, 1),
(6, 'DBSGSGSGXXX', 'DBS Bank Ltd', 'Singapore', 'SGD', 18.00, 1),
(7, 'EBILAEADXXX', 'Emirates NBD Bank PJSC', 'United Arab Emirates', 'USD', 25.00, 1),
(8, 'BNPAFRPPXXX', 'BNP Paribas S.A.', 'France', 'EUR', 14.00, 1),
(9, 'SBININBBXXX', 'State Bank of India (UK Branch)', 'United Kingdom', 'GBP', 12.00, 1),
(10, 'SBININBBXXX', 'State Bank of India (NY Branch)', 'United States', 'USD', 15.00, 1),
(11, 'CHASUS33XXX', 'JPMorgan Chase (London Branch)', 'United Kingdom', 'GBP', 10.00, 1),
(12, 'CHASUS33XXX', 'JPMorgan Chase (Frankfurt Branch)', 'Germany', 'EUR', 12.00, 1),
(13, 'BARCGB22XXX', 'Barclays Bank (NY Branch)', 'United States', 'USD', 15.00, 1),
(14, 'BARCGB22XXX', 'Barclays Bank (EU Branch)', 'Germany', 'EUR', 12.00, 1),
(15, 'DBSGSGSGXXX', 'DBS Bank (USD Clearing)', 'Singapore', 'USD', 20.00, 1);

-- 4. Initial Double-Entry Ledger Transactions (Opening Balances)
INSERT OR IGNORE INTO ledger_transactions (transaction_id, reference, transaction_type, description, created_at) VALUES
(1, 'TX-GENESIS-USD-01', 'ACCOUNT_DEPOSIT', 'Central vault opening deposit (USD)', '2026-11-20 09:00:00'),
(2, 'TX-GENESIS-EUR-01', 'ACCOUNT_DEPOSIT', 'Central vault opening deposit (EUR)', '2026-11-20 09:05:00'),
(3, 'TX-GENESIS-GBP-01', 'ACCOUNT_DEPOSIT', 'Central vault opening deposit (GBP)', '2026-11-20 09:10:00'),
(4, 'TX-GENESIS-INR-01', 'ACCOUNT_DEPOSIT', 'Central vault opening deposit (INR)', '2026-11-20 09:15:00'),
(5, 'TX-GENESIS-SGD-01', 'ACCOUNT_DEPOSIT', 'Central vault opening deposit (SGD)', '2026-11-20 09:20:00');

INSERT OR IGNORE INTO ledger_entries (entry_id, transaction_id, account_identifier, entry_type, amount, currency, created_at) VALUES
-- USD Opening
(1, 1, 'CENTRAL_CASH_VAULT_USD', 'CREDIT', 467500.00, 'USD', '2026-11-20 09:00:00'),
(2, 1, 'CUSTOMER_ACCOUNTS_USD', 'DEBIT', 467500.00, 'USD', '2026-11-20 09:00:00'),
-- EUR Opening
(3, 2, 'CENTRAL_CASH_VAULT_EUR', 'CREDIT', 94000.00, 'EUR', '2026-11-20 09:05:00'),
(4, 2, 'CUSTOMER_ACCOUNTS_EUR', 'DEBIT', 94000.00, 'EUR', '2026-11-20 09:05:00'),
-- GBP Opening
(5, 3, 'CENTRAL_CASH_VAULT_GBP', 'CREDIT', 62000.00, 'GBP', '2026-11-20 09:10:00'),
(6, 3, 'CUSTOMER_ACCOUNTS_GBP', 'DEBIT', 62000.00, 'GBP', '2026-11-20 09:10:00'),
-- INR Opening
(7, 4, 'CENTRAL_CASH_VAULT_INR', 'CREDIT', 850000.00, 'INR', '2026-11-20 09:15:00'),
(8, 4, 'CUSTOMER_ACCOUNTS_INR', 'DEBIT', 850000.00, 'INR', '2026-11-20 09:15:00'),
-- SGD Opening
(9, 5, 'CENTRAL_CASH_VAULT_SGD', 'CREDIT', 115000.00, 'SGD', '2026-11-20 09:20:00'),
(10, 5, 'CUSTOMER_ACCOUNTS_SGD', 'DEBIT', 115000.00, 'SGD', '2026-11-20 09:20:00');

-- 5. Seed Real Historical Payments (SETTLED, SCREENING, REJECTED)
INSERT OR IGNORE INTO payments (payment_id, reference_no, source_account_id, amount, currency, beneficiary_name, beneficiary_account, beneficiary_bic, status, status_reason, route_id, created_at, settled_at) VALUES
(1, 'PAY-89214-US', 3, 24500.00, 'USD', 'Apex Global Logistics LLC', 'US33CHAS9823412091', 'CHASUS33XXX', 'SETTLED', NULL, 1, '2026-11-21 10:15:00', '2026-11-21 10:15:04'),
(2, 'PAY-89215-EU', 4, 14200.00, 'EUR', 'Heidelberg Precision Instruments GmbH', 'DE89DEUT1009823411', 'DEUTDEDDFXX', 'SETTLED', NULL, 2, '2026-11-21 14:30:00', '2026-11-21 14:30:06'),
(3, 'PAY-89216-GB', 5, 8750.00, 'GBP', 'Cambridge Research Consulting Ltd', 'GB29BARC2009128374', 'BARCGB22XXX', 'SETTLED', NULL, 3, '2026-11-22 09:45:00', '2026-11-22 09:45:03'),
(4, 'PAY-89217-US', 2, 5200.00, 'USD', 'Nordic Maritime Supplies Corp', 'US33CHAS1102938475', 'CHASUS33XXX', 'SETTLED', NULL, 1, '2026-11-22 16:20:00', '2026-11-22 16:20:05'),
(5, 'PAY-89218-SG', 7, 75000.00, 'USD', 'Pacific Rim Energy Holdings Pte', 'SG88DBSG0098234101', 'DBSGSGSGXXX', 'SCREENING', 'High value threshold exceeded (USD 75,000.00 >= 50,000.00)', 6, '2026-11-23 08:30:00', NULL),
(6, 'PAY-89219-IN', 1, 125000.00, 'INR', 'Bangalore Software Systems Pvt Ltd', 'IN09SBIN0048210394', 'SBININBBXXX', 'SETTLED', NULL, 4, '2026-11-23 11:10:00', '2026-11-23 11:10:02');

-- 6. Settlement Ledger Postings for Settled Payments (Balanced Debits & Credits)
INSERT OR IGNORE INTO ledger_transactions (transaction_id, reference, transaction_type, description, created_at) VALUES
(6, 'SETTLE-PAY-89214-US', 'PAYMENT_SETTLEMENT', 'Settlement to Apex Global Logistics LLC via CHASUS33XXX', '2026-11-21 10:15:04'),
(7, 'SETTLE-PAY-89215-EU', 'PAYMENT_SETTLEMENT', 'Settlement to Heidelberg Precision Instruments via DEUTDEDDFXX', '2026-11-21 14:30:06'),
(8, 'SETTLE-PAY-89216-GB', 'PAYMENT_SETTLEMENT', 'Settlement to Cambridge Research Consulting via BARCGB22XXX', '2026-11-22 09:45:03'),
(9, 'SETTLE-PAY-89217-US', 'PAYMENT_SETTLEMENT', 'Settlement to Nordic Maritime Supplies via CHASUS33XXX', '2026-11-22 16:20:05'),
(10, 'SETTLE-PAY-89219-IN', 'PAYMENT_SETTLEMENT', 'Settlement to Bangalore Software Systems via SBININBBXXX', '2026-11-23 11:10:02');

INSERT OR IGNORE INTO ledger_entries (entry_id, transaction_id, account_identifier, entry_type, amount, currency, created_at) VALUES
-- Payment 1 (USD 24,500)
(11, 6, 'CPN-USD-20001', 'CREDIT', 24500.00, 'USD', '2026-11-21 10:15:04'),
(12, 6, 'NOSTRO_CLEARING_CHASUS33XXX', 'DEBIT', 24500.00, 'USD', '2026-11-21 10:15:04'),
-- Payment 2 (EUR 14,200)
(13, 7, 'CPN-EUR-30001', 'CREDIT', 14200.00, 'EUR', '2026-11-21 14:30:06'),
(14, 7, 'NOSTRO_CLEARING_DEUTDEDDFXX', 'DEBIT', 14200.00, 'EUR', '2026-11-21 14:30:06'),
-- Payment 3 (GBP 8,750)
(15, 8, 'CPN-GBP-40001', 'CREDIT', 8750.00, 'GBP', '2026-11-22 09:45:03'),
(16, 8, 'NOSTRO_CLEARING_BARCGB22XXX', 'DEBIT', 8750.00, 'GBP', '2026-11-22 09:45:03'),
-- Payment 4 (USD 5,200)
(17, 9, 'CPN-USD-10002', 'CREDIT', 5200.00, 'USD', '2026-11-22 16:20:05'),
(18, 9, 'NOSTRO_CLEARING_CHASUS33XXX', 'DEBIT', 5200.00, 'USD', '2026-11-22 16:20:05'),
-- Payment 6 (INR 125,000)
(19, 10, 'CPN-INR-10001', 'CREDIT', 125000.00, 'INR', '2026-11-23 11:10:02'),
(20, 10, 'NOSTRO_CLEARING_SBININBBXXX', 'DEBIT', 125000.00, 'INR', '2026-11-23 11:10:02');

-- 7. Seed Compliance Review for the Held Payment #5
INSERT OR IGNORE INTO compliance_reviews (review_id, payment_id, status, matched_rule, review_notes, created_at) VALUES
(1, 5, 'FLAGGED', 'High value threshold exceeded (USD 75,000.00 >= 50,000.00)', 'Transfer held for Enhanced Due Diligence (EDD) verification.', '2026-11-23 08:30:00');

-- 8. Seed pacs.008 Messages for Settled Payments
INSERT OR IGNORE INTO payment_messages (message_id, payment_id, message_type, sender_bic, receiver_bic, payload_summary, digest_hash, created_at) VALUES
(1, 1, 'pacs.008.001.08', 'CPNETUS33XXX', 'CHASUS33XXX', '<pacs.008><MsgId>PAY-89214-US</MsgId><Amt Ccy="USD">24500.00</Amt></pacs.008>', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', '2026-11-21 10:15:04'),
(2, 2, 'pacs.008.001.08', 'CPNETUS33XXX', 'DEUTDEDDFXX', '<pacs.008><MsgId>PAY-89215-EU</MsgId><Amt Ccy="EUR">14200.00</Amt></pacs.008>', '4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a', '2026-11-21 14:30:06'),
(3, 3, 'pacs.008.001.08', 'CPNETUS33XXX', 'BARCGB22XXX', '<pacs.008><MsgId>PAY-89216-GB</MsgId><Amt Ccy="GBP">8750.00</Amt></pacs.008>', 'ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39d', '2026-11-22 09:45:03'),
(4, 4, 'pacs.008.001.08', 'CPNETUS33XXX', 'CHASUS33XXX', '<pacs.008><MsgId>PAY-89217-US</MsgId><Amt Ccy="USD">5200.00</Amt></pacs.008>', '8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4', '2026-11-22 16:20:05'),
(5, 6, 'pacs.008.001.08', 'CPNETUS33XXX', 'SBININBBXXX', '<pacs.008><MsgId>PAY-89219-IN</MsgId><Amt Ccy="INR">125000.00</Amt></pacs.008>', 'a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3', '2026-11-23 11:10:02');
