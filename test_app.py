import unittest
import sqlite3
import json
from app import app
from database.db import init_db, get_db

class TestCrossPayNetDBMS(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        init_db(reset=True)
        app.config["TESTING"] = True
        cls.client = app.test_client()

    def test_01_database_tables_and_views_exist(self):
        conn = get_db()
        cursor = conn.cursor()
        cursor.execute("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view', 'trigger')")
        objects = {row["name"]: row["type"] for row in cursor.fetchall()}
        conn.close()

        # Check required tables
        expected_tables = [
            "customers", "accounts", "fund_reservations", "correspondent_routes",
            "payments", "compliance_reviews", "ledger_transactions", "ledger_entries", "payment_messages"
        ]
        for tbl in expected_tables:
            self.assertIn(tbl, objects, f"Missing table {tbl}")

        # Check required views
        expected_views = ["view_account_summary", "view_payment_details", "view_trial_balance"]
        for v in expected_views:
            self.assertIn(v, objects, f"Missing view {v}")

        # Check required triggers
        expected_triggers = ["trg_validate_fund_reservation", "trg_increment_reserved_amount", "trg_update_reservation_status"]
        for trg in expected_triggers:
            self.assertIn(trg, objects, f"Missing trigger {trg}")

    def test_02_dashboard_route(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"CrossPayNet", response.data)
        self.assertIn(b"Operations & Settlement Dashboard", response.data)

    def test_03_customer_and_account_crud(self):
        # 1. Create Customer
        res = self.client.post("/accounts/create-customer", data={
            "full_name": "Test User",
            "email": "test.user@univ.edu",
            "country": "USA"
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"registered successfully", res.data)

        # 2. Get customer ID
        conn = get_db()
        cursor = conn.cursor()
        cursor.execute("SELECT customer_id FROM customers WHERE email = 'test.user@univ.edu'")
        cust_id = cursor.fetchone()["customer_id"]

        # 3. Create Account with opening deposit
        res = self.client.post("/accounts/create-account", data={
            "customer_id": cust_id,
            "currency": "USD",
            "initial_deposit": "5000.00"
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"created with balance", res.data)

        # 4. Deposit additional funds
        cursor.execute("SELECT account_id FROM accounts WHERE customer_id = ?", (cust_id,))
        acc_id = cursor.fetchone()["account_id"]
        conn.close()

        res = self.client.post("/accounts/deposit", data={
            "account_id": acc_id,
            "amount": "1500.00"
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"Deposited", res.data)

    def test_04_payment_settlement_workflow(self):
        # Initiate payment from Account #2 (Sarah Jenkins, USD balance > 10,000)
        res = self.client.post("/payments/initiate", data={
            "source_account_id": 2,
            "amount": "250.00",
            "beneficiary_name": "Alice Wonderland",
            "beneficiary_account": "US33CHAS00998877",
            "beneficiary_bic": "CHASUS33XXX"
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"successfully settled and dispatched", res.data)

        # Check ledger entries for this settlement
        conn = get_db()
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM view_trial_balance WHERE currency = 'USD'")
        tb = cursor.fetchone()
        self.assertEqual(tb["balance_difference"], 0.0, "Trial balance for USD is not balanced!")

        # Verify pacs.008 message was generated
        cursor.execute("SELECT * FROM payment_messages ORDER BY message_id DESC LIMIT 1")
        msg = cursor.fetchone()
        self.assertIsNotNone(msg)
        self.assertEqual(msg["receiver_bic"], "CHASUS33XXX")
        conn.close()

    def test_05_compliance_sanctions_screening(self):
        # Initiate payment with sanctioned keyword
        res = self.client.post("/payments/initiate", data={
            "source_account_id": 2,
            "amount": "100.00",
            "beneficiary_name": "SANCTIONED Entity XYZ",
            "beneficiary_account": "US33CHAS11223344",
            "beneficiary_bic": "CHASUS33XXX"
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"Compliance Review", res.data)

        # Verify it is flagged in compliance_reviews
        conn = get_db()
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM compliance_reviews WHERE status = 'FLAGGED' ORDER BY review_id DESC LIMIT 1")
        review = cursor.fetchone()
        self.assertIsNotNone(review)
        review_id = review["review_id"]
        conn.close()

        # Approve the flagged review
        res = self.client.post("/compliance/action", data={
            "review_id": review_id,
            "action": "APPROVE",
            "notes": "Verified legitimate transfer after enhanced due diligence."
        }, follow_redirects=True)
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"cleared and settled successfully", res.data)

    def test_06_sql_query_console(self):
        # Run query through SQL console
        res = self.client.post("/query", data={
            "sql_query": "SELECT customer_name, account_number, available_balance FROM view_account_summary LIMIT 3;"
        })
        self.assertEqual(res.status_code, 200)
        self.assertIn(b"Query Result Set", res.data)
        self.assertIn(b"available_balance", res.data)

    def test_07_trigger_prevents_excessive_reservation(self):
        conn = get_db()
        cursor = conn.cursor()
        # Account #2 balance
        cursor.execute("SELECT (balance - reserved_amount) AS avail FROM accounts WHERE account_id = 2")
        avail = cursor.fetchone()["avail"]

        # Attempt to insert reservation greater than available balance
        with self.assertRaises(sqlite3.IntegrityError):
            cursor.execute(
                "INSERT INTO fund_reservations (account_id, amount, currency, status, reason) VALUES (2, ?, 'USD', 'ACTIVE', 'Test excess')",
                (avail + 100000.00,)
            )
        conn.close()

if __name__ == "__main__":
    unittest.main()
