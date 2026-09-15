"""Versioned, transactional schema bootstrap; append migrations for future changes."""
from sqlalchemy import text
from .core import Base, decrypt, encrypt, engine


def _upgrade_imap_secrets(conn):
    """Backfill explicit IMAP/SMTP endpoints for accounts bound before generic connections."""
    rows = conn.execute(text("SELECT id, secret FROM gmail_accounts WHERE provider = 'imap'")).fetchall()
    for identifier, stored in rows:
        try:
            value = decrypt(stored)
        except Exception:
            conn.execute(text("UPDATE gmail_accounts SET status = 'reconnect' WHERE id = :id"), {'id': identifier})
            continue
        if not isinstance(value, dict) or ('imap' in value and 'smtp' in value):
            continue
        port = value.get('port', 465)
        value['imap'] = value.get('imap') or {'host': 'imap.gmail.com', 'port': 993, 'security': 'ssl'}
        value['smtp'] = value.get('smtp') or {'host': 'smtp.gmail.com', 'port': port, 'security': 'ssl' if port == 465 else 'starttls'}
        value.pop('port', None)
        conn.execute(text('UPDATE gmail_accounts SET secret = :secret WHERE id = :id'), {'secret': encrypt(value), 'id': identifier})


def migrate():
    with engine.begin() as conn:
        if conn.dialect.name == 'postgresql':
            conn.execute(text('SELECT pg_advisory_xact_lock(8183001)'))
        conn.execute(text('CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY)'))
        if not conn.execute(text('SELECT version FROM schema_migrations WHERE version=1')).first():
            Base.metadata.create_all(conn)
            conn.execute(text('INSERT INTO schema_migrations(version) VALUES (1)'))
        if not conn.execute(text('SELECT version FROM schema_migrations WHERE version=2')).first():
            _upgrade_imap_secrets(conn)
            conn.execute(text('INSERT INTO schema_migrations(version) VALUES (2)'))


if __name__ == '__main__':
    migrate()
