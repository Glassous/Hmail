"""Versioned, transactional schema bootstrap; append migrations for future changes."""
from sqlalchemy import text
from .core import Base, engine


def migrate():
    with engine.begin() as conn:
        if conn.dialect.name == 'postgresql':
            conn.execute(text('SELECT pg_advisory_xact_lock(8183001)'))
        conn.execute(text('CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY)'))
        if not conn.execute(text('SELECT version FROM schema_migrations WHERE version=1')).first():
            Base.metadata.create_all(conn)
            conn.execute(text('INSERT INTO schema_migrations(version) VALUES (1)'))


if __name__ == '__main__':
    migrate()
