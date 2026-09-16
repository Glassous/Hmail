"""Versioned, transactional schema bootstrap; append migrations for future changes."""
from datetime import datetime, timedelta, timezone

from sqlalchemy import text
from .core import Base, decrypt, encrypt, engine


def _backfill_account_created_at(conn):
    """旧数据没有创建时间：按最近访问时间近似还原先后，保证列表里越早的邮箱越靠上。"""
    base = datetime(2000, 1, 1, tzinfo=timezone.utc)
    rows = conn.execute(text('SELECT id FROM gmail_accounts ORDER BY accessed_at ASC, id ASC')).fetchall()
    for index, (identifier,) in enumerate(rows):
        conn.execute(text('UPDATE gmail_accounts SET created_at = :created WHERE id = :id'),
                     {'created': base + timedelta(seconds=index), 'id': identifier})


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
        if not conn.execute(text('SELECT version FROM schema_migrations WHERE version=3')).first():
            from sqlalchemy import inspect
            columns = {column['name'] for column in inspect(conn).get_columns('gmail_accounts')}
            for name, definition in [('credential_version', 'INTEGER NOT NULL DEFAULT 1'), ('write_revision', 'INTEGER NOT NULL DEFAULT 0'), ('accessed_at', 'FLOAT NOT NULL DEFAULT 0')]:
                if name not in columns:
                    conn.execute(text(f'ALTER TABLE gmail_accounts ADD COLUMN {name} {definition}'))
            Base.metadata.create_all(conn)
            conn.execute(text('INSERT INTO schema_migrations(version) VALUES (3)'))
        if not conn.execute(text('SELECT version FROM schema_migrations WHERE version=4')).first():
            from sqlalchemy import inspect
            columns = {column['name'] for column in inspect(conn).get_columns('users')}
            if 'default_account_id' not in columns:
                conn.execute(text("ALTER TABLE users ADD COLUMN default_account_id VARCHAR(36) NOT NULL DEFAULT ''"))
            conn.execute(text('INSERT INTO schema_migrations(version) VALUES (4)'))
        if not conn.execute(text('SELECT version FROM schema_migrations WHERE version=5')).first():
            from sqlalchemy import inspect
            columns = {column['name'] for column in inspect(conn).get_columns('gmail_accounts')}
            if 'created_at' not in columns:
                # 不能在这里用 CURRENT_TIMESTAMP 作默认值：SQLite 的 ADD COLUMN 不接受它。
                conn.execute(text('ALTER TABLE gmail_accounts ADD COLUMN created_at TIMESTAMP WITH TIME ZONE'))
            _backfill_account_created_at(conn)
            conn.execute(text('INSERT INTO schema_migrations(version) VALUES (5)'))


if __name__ == '__main__':
    migrate()
