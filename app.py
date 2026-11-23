import os
import hashlib
import time
import uuid
from datetime import datetime
from flask import Flask, render_template, request, redirect, url_for, flash, jsonify
from database.db import get_db, init_db

app = Flask(__name__)
app.secret_key = "crosspaynet-college-dbms-key-2026"

SANCTIONED_KEYWORDS = ["SANCTION", "EMBARGO", "TERROR", "PROHIBITED", "BLACKLIST"]
HIGH_VALUE_THRESHOLD = 50000.00

# -------------------------------------------------------------
# Home / Dashboard
# -------------------------------------------------------------
@app.route("/")
def dashboard():
    conn = get_db()
    cursor = conn.cursor()

    # DBMS Aggregation Queries
    cursor.execute("SELECT COUNT(*) FROM customers")
    total_customers = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*), COALESCE(SUM(balance), 0) FROM accounts")
    row = cursor.fetchone()
    total_accounts = row[0]
    total_deposits = row[1]

    cursor.execute("SELECT COUNT(*) FROM payments WHERE status = 'SETTLED'")
    settled_payments_count = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM payments WHERE status = 'SETTLED'")
    row = cursor.fetchone()
    settled_volume = row[1]

    cursor.execute("SELECT COUNT(*) FROM payments WHERE status = 'SCREENING'")
    held_payments_count = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*) FROM correspondent_routes WHERE is_active = 1")
    active_corridors = cursor.fetchone()[0]

    # Trial Balance summary from VIEW
    cursor.execute("SELECT * FROM view_trial_balance")
    trial_balances = cursor.fetchall()

    # Recent payments from VIEW
    cursor.execute("SELECT * FROM view_payment_details ORDER BY payment_id DESC LIMIT 8")
    recent_payments = cursor.fetchall()

    conn.close()
    return render_template(
        "index.html",
        total_customers=total_customers,
        total_accounts=total_accounts,
        total_deposits=total_deposits,
        settled_payments_count=settled_payments_count,
        settled_volume=settled_volume,
        held_payments_count=held_payments_count,
        active_corridors=active_corridors,
        trial_balances=trial_balances,
        recent_payments=recent_payments,
    )

# -------------------------------------------------------------
# Accounts & Customers (CRUD)
# -------------------------------------------------------------
@app.route("/accounts")
def accounts():
    conn = get_db()
    cursor = conn.cursor()

    # Read from Database VIEW
    cursor.execute("SELECT * FROM view_account_summary ORDER BY account_id ASC")
    accounts_list = cursor.fetchall()

    cursor.execute("SELECT * FROM customers ORDER BY customer_id ASC")
    customers_list = cursor.fetchall()

    conn.close()
    return render_template("accounts.html", accounts=accounts_list, customers=customers_list)

@app.route("/accounts/create-customer", methods=["POST"])
def create_customer():
    name = request.form.get("full_name", "").strip()
    email = request.form.get("email", "").strip()
    country = request.form.get("country", "").strip().upper()

    if not name or not email or not country:
        flash("All customer fields are required.", "danger")
        return redirect(url_for("accounts"))

    conn = get_db()
    try:
        conn.execute(
            "INSERT INTO customers (full_name, email, country) VALUES (?, ?, ?)",
            (name, email, country),
        )
        conn.commit()
        flash(f"Customer '{name}' registered successfully.", "success")
    except Exception as e:
        flash(f"Error creating customer: {e}", "danger")
    finally:
        conn.close()

    return redirect(url_for("accounts"))

@app.route("/accounts/create-account", methods=["POST"])
def create_account():
    customer_id = request.form.get("customer_id")
    currency = request.form.get("currency", "USD").upper()
    initial_deposit = float(request.form.get("initial_deposit", 0.0))

    if not customer_id or initial_deposit < 0:
        flash("Invalid customer or deposit amount.", "danger")
        return redirect(url_for("accounts"))

    conn = get_db()
    cursor = conn.cursor()
    try:
        # Generate deterministic account number
        cursor.execute("SELECT COUNT(*) FROM accounts")
        count = cursor.fetchone()[0] + 10001
        acc_num = f"CPN-{currency}-{count}"

        # Insert account
        cursor.execute(
            "INSERT INTO accounts (customer_id, account_number, currency, balance, reserved_amount) VALUES (?, ?, ?, ?, 0.00)",
            (customer_id, acc_num, currency, initial_deposit),
        )
        conn.commit()

        # If deposit > 0, post double-entry ledger record
        if initial_deposit > 0:
            tx_ref = f"DEP-{acc_num}-{int(time.time())}-{uuid.uuid4().hex[:6].upper()}"
            cursor.execute(
                "INSERT INTO ledger_transactions (reference, transaction_type, description) VALUES (?, 'ACCOUNT_DEPOSIT', ?)",
                (tx_ref, f"Opening balance deposit for {acc_num}"),
            )
            tx_id = cursor.lastrowid
            cursor.execute(
                "INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'CREDIT', ?, ?)",
                (tx_id, f"CENTRAL_VAULT_{currency}", initial_deposit, currency),
            )
            cursor.execute(
                "INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'DEBIT', ?, ?)",
                (tx_id, acc_num, initial_deposit, currency),
            )
            conn.commit()

        flash(f"Account {acc_num} created with balance {currency} {initial_deposit:,.2f}.", "success")
    except Exception as e:
        flash(f"Error creating account: {e}", "danger")
    finally:
        conn.close()

    return redirect(url_for("accounts"))

@app.route("/accounts/deposit", methods=["POST"])
def deposit_funds():
    account_id = request.form.get("account_id")
    amount = float(request.form.get("amount", 0.0))

    if not account_id or amount <= 0:
        flash("Deposit amount must be greater than zero.", "danger")
        return redirect(url_for("accounts"))

    conn = get_db()
    cursor = conn.cursor()
    try:
        cursor.execute("SELECT account_number, currency FROM accounts WHERE account_id = ?", (account_id,))
        acc = cursor.fetchone()
        if not acc:
            flash("Account not found.", "danger")
            return redirect(url_for("accounts"))

        acc_num, currency = acc["account_number"], acc["currency"]

        # Atomic transaction: Update balance & write double-entry ledger entries
        cursor.execute("UPDATE accounts SET balance = balance + ? WHERE account_id = ?", (amount, account_id))
        
        tx_ref = f"DEP-{acc_num}-{int(time.time())}-{uuid.uuid4().hex[:6].upper()}"
        cursor.execute(
            "INSERT INTO ledger_transactions (reference, transaction_type, description) VALUES (?, 'ACCOUNT_DEPOSIT', ?)",
            (tx_ref, f"Funds deposit into {acc_num}"),
        )
        tx_id = cursor.lastrowid
        cursor.execute(
            "INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'CREDIT', ?, ?)",
            (tx_id, f"CENTRAL_VAULT_{currency}", amount, currency),
        )
        cursor.execute(
            "INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'DEBIT', ?, ?)",
            (tx_id, acc_num, amount, currency),
        )
        conn.commit()
        flash(f"Deposited {currency} {amount:,.2f} into {acc_num}.", "success")
    except Exception as e:
        flash(f"Deposit error: {e}", "danger")
    finally:
        conn.close()

    return redirect(url_for("accounts"))

# -------------------------------------------------------------
# Payments & Cross-Border Settlement Workflow
# -------------------------------------------------------------
@app.route("/payments")
def payments():
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("SELECT * FROM view_payment_details ORDER BY payment_id DESC")
    payment_list = cursor.fetchall()

    cursor.execute("SELECT account_id, account_number, currency, (balance - reserved_amount) AS available FROM accounts WHERE status = 'ACTIVE'")
    available_accounts = cursor.fetchall()

    cursor.execute("SELECT * FROM correspondent_routes WHERE is_active = 1")
    routes = cursor.fetchall()

    conn.close()
    return render_template("payments.html", payments=payment_list, accounts=available_accounts, routes=routes)

@app.route("/payments/initiate", methods=["POST"])
def initiate_payment():
    source_account_id = request.form.get("source_account_id")
    amount = float(request.form.get("amount", 0.0))
    beneficiary_name = request.form.get("beneficiary_name", "").strip()
    beneficiary_account = request.form.get("beneficiary_account", "").strip()
    beneficiary_bic = request.form.get("beneficiary_bic", "").strip().upper()

    if not source_account_id or amount <= 0 or not beneficiary_name or not beneficiary_account or not beneficiary_bic:
        flash("All payment transfer fields are required.", "danger")
        return redirect(url_for("payments"))

    conn = get_db()
    cursor = conn.cursor()

    try:
        # 1. Fetch source account details
        cursor.execute("SELECT account_id, account_number, currency, balance, reserved_amount FROM accounts WHERE account_id = ?", (source_account_id,))
        acc = cursor.fetchone()
        if not acc:
            flash("Source account not found.", "danger")
            return redirect(url_for("payments"))

        acc_currency = acc["currency"]
        available_balance = acc["balance"] - acc["reserved_amount"]

        if available_balance < amount:
            flash(f"Insufficient funds! Available: {acc_currency} {available_balance:,.2f}, Required: {acc_currency} {amount:,.2f}", "danger")
            return redirect(url_for("payments"))

        # Generate unique payment reference
        ref_no = f"PAY-{int(time.time())}-{acc['account_id']}-{uuid.uuid4().hex[:4].upper()}"

        # 2. Check Sanctions & AML Compliance Rules
        flagged = False
        match_rule = None
        for kw in SANCTIONED_KEYWORDS:
            if kw in beneficiary_name.upper():
                flagged = True
                match_rule = f"Sanction keyword match: '{kw}'"
                break
        if not flagged and amount >= HIGH_VALUE_THRESHOLD:
            flagged = True
            match_rule = f"High value threshold exceeded ({acc_currency} {amount:,.2f} >= {HIGH_VALUE_THRESHOLD:,.2f})"

        # 3. Insert payment record (Pending state)
        cursor.execute(
            """INSERT INTO payments (reference_no, source_account_id, amount, currency, beneficiary_name, beneficiary_account, beneficiary_bic, status, status_reason)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (ref_no, source_account_id, amount, acc_currency, beneficiary_name, beneficiary_account, beneficiary_bic, 
             'SCREENING' if flagged else 'PENDING', match_rule if flagged else None),
        )
        payment_id = cursor.lastrowid

        if flagged:
            # Create Compliance Review record for manual audit
            cursor.execute(
                "INSERT INTO compliance_reviews (payment_id, status, matched_rule, review_notes) VALUES (?, 'FLAGGED', ?, ?)",
                (payment_id, match_rule, "Transaction held pending compliance officer decision."),
            )
            conn.commit()
            flash(f"Payment {ref_no} initiated but held for Compliance Review ({match_rule}).", "warning")
            return redirect(url_for("compliance"))

        # 4. Find matching Correspondent Route by BIC and Currency
        cursor.execute(
            "SELECT route_id, bank_name FROM correspondent_routes WHERE destination_bic = ? AND currency = ? AND is_active = 1",
            (beneficiary_bic, acc_currency),
        )
        route = cursor.fetchone()
        if not route:
            # No route found - reject payment
            cursor.execute(
                "UPDATE payments SET status = 'REJECTED', status_reason = ? WHERE payment_id = ?",
                (f"No active clearing route for BIC {beneficiary_bic} with currency {acc_currency}", payment_id),
            )
            conn.commit()
            flash(f"Payment rejected: No active route for BIC {beneficiary_bic} in {acc_currency}.", "danger")
            return redirect(url_for("payments"))

        route_id = route["route_id"]

        # 5. Reserve Funds (Triggers trg_validate_fund_reservation and trg_increment_reserved_amount)
        cursor.execute(
            "INSERT INTO fund_reservations (account_id, amount, currency, status, reason) VALUES (?, ?, ?, 'ACTIVE', ?)",
            (source_account_id, amount, acc_currency, f"Reservation for payment {ref_no}"),
        )
        reservation_id = cursor.lastrowid

        # 6. Settle Payment
        # Deduct balance & consume reservation
        cursor.execute("UPDATE accounts SET balance = balance - ? WHERE account_id = ?", (amount, source_account_id))
        cursor.execute("UPDATE fund_reservations SET status = 'CONSUMED' WHERE reservation_id = ?", (reservation_id,))

        # 7. Post Double-Entry Ledger Transaction
        tx_ref = f"SETTLE-{ref_no}"
        cursor.execute(
            "INSERT INTO ledger_transactions (reference, transaction_type, description) VALUES (?, 'PAYMENT_SETTLEMENT', ?)",
            (tx_ref, f"Cross-border settlement to {beneficiary_name} via {beneficiary_bic}"),
        )
        tx_id = cursor.lastrowid

        # Ledger double entry lines:
        # Credit customer account (reducing their claim), Debit nostro/clearing account
        cursor.execute(
            "INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'CREDIT', ?, ?)",
            (tx_id, acc["account_number"], amount, acc_currency),
        )
        cursor.execute(
            "INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'DEBIT', ?, ?)",
            (tx_id, f"NOSTRO_CLEARING_{beneficiary_bic}", amount, acc_currency),
        )

        # 8. Generate Financial Message (pacs.008 simulation)
        payload = f"<pacs.008><GrpHdr><MsgId>{ref_no}</MsgId></GrpHdr><Dbtr>{acc['account_number']}</Dbtr><Cdtr>{beneficiary_account}</Cdtr><Amt Ccy='{acc_currency}'>{amount:.2f}</Amt></pacs.008>"
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()

        cursor.execute(
            """INSERT INTO payment_messages (payment_id, message_type, sender_bic, receiver_bic, payload_summary, digest_hash)
               VALUES (?, 'pacs.008.001.08', 'CPNETUS33XXX', ?, ?, ?)""",
            (payment_id, beneficiary_bic, payload, digest),
        )

        # 9. Update Payment to SETTLED
        cursor.execute(
            "UPDATE payments SET status = 'SETTLED', route_id = ?, settled_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
            (route_id, payment_id),
        )

        conn.commit()
        flash(f"Payment {ref_no} for {acc_currency} {amount:,.2f} successfully settled and dispatched via {route['bank_name']}!", "success")

    except Exception as e:
        conn.rollback()
        flash(f"Transaction failed: {e}", "danger")
    finally:
        conn.close()

    return redirect(url_for("payments"))

# -------------------------------------------------------------
# Compliance & Sanctions Reviews
# -------------------------------------------------------------
@app.route("/compliance")
def compliance():
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("""
        SELECT cr.*, p.reference_no, p.amount, p.currency, p.beneficiary_name, p.beneficiary_bic,
               a.account_number AS sender_account, c.full_name AS sender_name
        FROM compliance_reviews cr
        JOIN payments p ON cr.payment_id = p.payment_id
        JOIN accounts a ON p.source_account_id = a.account_id
        JOIN customers c ON a.customer_id = c.customer_id
        ORDER BY cr.review_id DESC
    """)
    reviews = cursor.fetchall()
    conn.close()
    return render_template("compliance.html", reviews=reviews)

@app.route("/compliance/action", methods=["POST"])
def compliance_action():
    review_id = request.form.get("review_id")
    action = request.form.get("action")  # 'APPROVE' or 'REJECT'
    notes = request.form.get("notes", "").strip()

    conn = get_db()
    cursor = conn.cursor()

    try:
        cursor.execute("SELECT cr.*, p.* FROM compliance_reviews cr JOIN payments p ON cr.payment_id = p.payment_id WHERE cr.review_id = ?", (review_id,))
        record = cursor.fetchone()
        if not record:
            flash("Review record not found.", "danger")
            return redirect(url_for("compliance"))

        payment_id = record["payment_id"]
        source_account_id = record["source_account_id"]
        amount = record["amount"]
        currency = record["currency"]
        beneficiary_bic = record["beneficiary_bic"]
        beneficiary_name = record["beneficiary_name"]
        ref_no = record["reference_no"]

        if action == "REJECT":
            cursor.execute("UPDATE compliance_reviews SET status = 'REJECTED', review_notes = ? WHERE review_id = ?", (notes or "Rejected by Compliance Officer", review_id))
            cursor.execute("UPDATE payments SET status = 'REJECTED', status_reason = ? WHERE payment_id = ?", (f"Rejected by Compliance: {notes}", payment_id))
            conn.commit()
            flash(f"Payment {ref_no} rejected due to sanctions/compliance violation.", "info")

        elif action == "APPROVE":
            cursor.execute("UPDATE compliance_reviews SET status = 'PASSED', review_notes = ? WHERE review_id = ?", (notes or "Approved by Compliance Officer", review_id))
            
            # Check route
            cursor.execute("SELECT route_id, bank_name FROM correspondent_routes WHERE destination_bic = ? AND currency = ? AND is_active = 1", (beneficiary_bic, currency))
            route = cursor.fetchone()
            if not route:
                cursor.execute("UPDATE payments SET status = 'REJECTED', status_reason = ? WHERE payment_id = ?", (f"No route for {beneficiary_bic}", payment_id))
                conn.commit()
                flash("Compliance passed, but no routing corridor was available.", "warning")
                return redirect(url_for("compliance"))

            # Settle payment & debit customer account
            cursor.execute("UPDATE accounts SET balance = balance - ? WHERE account_id = ?", (amount, source_account_id))
            
            # Ledger entries
            cursor.execute("SELECT account_number FROM accounts WHERE account_id = ?", (source_account_id,))
            acc_num = cursor.fetchone()["account_number"]

            tx_ref = f"SETTLE-{ref_no}"
            cursor.execute(
                "INSERT INTO ledger_transactions (reference, transaction_type, description) VALUES (?, 'PAYMENT_SETTLEMENT', ?)",
                (tx_ref, f"Approved settlement to {beneficiary_name} via {beneficiary_bic}"),
            )
            tx_id = cursor.lastrowid
            cursor.execute("INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'CREDIT', ?, ?)", (tx_id, acc_num, amount, currency))
            cursor.execute("INSERT INTO ledger_entries (transaction_id, account_identifier, entry_type, amount, currency) VALUES (?, ?, 'DEBIT', ?, ?)", (tx_id, f"NOSTRO_CLEARING_{beneficiary_bic}", amount, currency))

            # pacs.008 message
            payload = f"<pacs.008><GrpHdr><MsgId>{ref_no}</MsgId></GrpHdr><Dbtr>{acc_num}</Dbtr><Cdtr>{record['beneficiary_account']}</Cdtr><Amt Ccy='{currency}'>{amount:.2f}</Amt></pacs.008>"
            digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
            cursor.execute(
                "INSERT INTO payment_messages (payment_id, message_type, sender_bic, receiver_bic, payload_summary, digest_hash) VALUES (?, 'pacs.008.001.08', 'CPNETUS33XXX', ?, ?, ?)",
                (payment_id, beneficiary_bic, payload, digest),
            )

            cursor.execute("UPDATE payments SET status = 'SETTLED', route_id = ?, settled_at = CURRENT_TIMESTAMP WHERE payment_id = ?", (route["route_id"], payment_id))
            conn.commit()
            flash(f"Payment {ref_no} cleared and settled successfully!", "success")

    except Exception as e:
        conn.rollback()
        flash(f"Error handling compliance review: {e}", "danger")
    finally:
        conn.close()

    return redirect(url_for("compliance"))

# -------------------------------------------------------------
# Double-Entry Ledger & Trial Balance
# -------------------------------------------------------------
@app.route("/ledger")
def ledger():
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("SELECT * FROM view_trial_balance")
    trial_balance = cursor.fetchall()

    cursor.execute("""
        SELECT lt.transaction_id, lt.reference, lt.transaction_type, lt.description, lt.created_at,
               le.entry_id, le.account_identifier, le.entry_type, le.amount, le.currency
        FROM ledger_transactions lt
        JOIN ledger_entries le ON lt.transaction_id = le.transaction_id
        ORDER BY lt.transaction_id DESC, le.entry_type ASC
    """)
    entries = cursor.fetchall()

    conn.close()
    return render_template("ledger.html", trial_balance=trial_balance, entries=entries)

# -------------------------------------------------------------
# Correspondent Routing Management
# -------------------------------------------------------------
@app.route("/routing")
def routing():
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM correspondent_routes ORDER BY route_id ASC")
    routes = cursor.fetchall()
    conn.close()
    return render_template("routing.html", routes=routes)

@app.route("/routing/add", methods=["POST"])
def add_route():
    bic = request.form.get("destination_bic", "").strip().upper()
    bank = request.form.get("bank_name", "").strip()
    country = request.form.get("country", "").strip()
    currency = request.form.get("currency", "USD").upper()
    fee = float(request.form.get("fee", 15.0))

    if not bic or not bank or not country:
        flash("All route fields are required.", "danger")
        return redirect(url_for("routing"))

    conn = get_db()
    try:
        conn.execute(
            "INSERT INTO correspondent_routes (destination_bic, bank_name, country, currency, fee) VALUES (?, ?, ?, ?, ?)",
            (bic, bank, country, currency, fee),
        )
        conn.commit()
        flash(f"Correspondent corridor for {bank} ({bic}) added.", "success")
    except Exception as e:
        flash(f"Error adding route: {e}", "danger")
    finally:
        conn.close()

    return redirect(url_for("routing"))

@app.route("/routing/toggle/<int:route_id>", methods=["POST"])
def toggle_route(route_id):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("UPDATE correspondent_routes SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END WHERE route_id = ?", (route_id,))
    conn.commit()
    conn.close()
    flash("Route status toggled.", "info")
    return redirect(url_for("routing"))

# -------------------------------------------------------------
# SQL Console & Schema Explorer (Viva & DBMS Demonstration)
# -------------------------------------------------------------
@app.route("/query", methods=["GET", "POST"])
def query_console():
    query_result = None
    columns = []
    error = None
    sql_text = ""

    conn = get_db()
    cursor = conn.cursor()

    # Get list of all tables and views in database
    cursor.execute("SELECT type, name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name")
    schema_objects = cursor.fetchall()

    if request.method == "POST":
        sql_text = request.form.get("sql_query", "").strip()
        if sql_text:
            try:
                cursor.execute(sql_text)
                if cursor.description:
                    columns = [d[0] for d in cursor.description]
                    query_result = cursor.fetchall()
                else:
                    conn.commit()
                    query_result = [{"rows_affected": cursor.rowcount}]
                    columns = ["rows_affected"]
            except Exception as e:
                error = str(e)

    conn.close()
    return render_template("query.html", schema_objects=schema_objects, query_result=query_result, columns=columns, error=error, sql_text=sql_text)

# -------------------------------------------------------------
# Reset Database to Seed State
# -------------------------------------------------------------
@app.route("/reset-db", methods=["POST"])
def reset_database():
    try:
        init_db(reset=True)
        flash("Database has been reset and seeded to initial demonstration state.", "success")
    except Exception as e:
        flash(f"Error resetting database: {e}", "danger")
    return redirect(url_for("dashboard"))

if __name__ == "__main__":
    init_db(reset=False)
    print("=" * 60)
    print("CrossPayNet: College-Level Relational DBMS Simulator")
    print("Running on http://127.0.0.1:5000")
    print("=" * 60)
    app.run(debug=True, host="127.0.0.1", port=5000)
