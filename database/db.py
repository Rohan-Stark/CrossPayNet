import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "crosspaynet.db")
SCHEMA_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "schema.sql")
SEED_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "seed.sql")

def init_db(reset=False):
    """Initializes the database schema and seeds initial demonstration data."""
    if reset and os.path.exists(DB_PATH):
        try:
            os.remove(DB_PATH)
        except Exception:
            pass

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON;")
    cursor = conn.cursor()

    with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
        cursor.executescript(f.read())

    # Only seed if no customers exist yet
    cursor.execute("SELECT COUNT(*) FROM customers")
    if cursor.fetchone()[0] == 0:
        with open(SEED_PATH, "r", encoding="utf-8") as f:
            cursor.executescript(f.read())

    conn.commit()
    conn.close()

def get_db():
    """
    Returns an active SQLite connection.
    Self-heals and auto-bootstraps schema and seed data if the database file is missing or uninitialized.
    """
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON;")

    # Check if tables are missing (e.g. freshly cloned repo or newly created file)
    cursor = conn.cursor()
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='customers'")
    if not cursor.fetchone():
        with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
            cursor.executescript(f.read())
        with open(SEED_PATH, "r", encoding="utf-8") as f:
            cursor.executescript(f.read())
        conn.commit()

    return conn

if __name__ == "__main__":
    init_db(reset=True)
    print("Database initialized successfully at:", DB_PATH)
