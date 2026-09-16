import hashlib
import hmac
import json
import os
import uuid
from datetime import datetime, timezone

from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, InvalidHashError
from cryptography.fernet import Fernet
from redis import Redis
from sqlalchemy import create_engine, String, Text, Integer, ForeignKey, DateTime, Float, Index, Boolean, text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

PUBLIC_URL = os.getenv('PUBLIC_URL', 'http://localhost:5173').rstrip('/')
GOOGLE_ID = os.getenv('GOOGLE_CLIENT_ID', '')
GOOGLE_SECRET = os.getenv('GOOGLE_CLIENT_SECRET', '')
REDIRECT_URI = PUBLIC_URL + '/api/v1/gmail-accounts/oauth/callback'
SCOPES = ['https://www.googleapis.com/auth/gmail.modify']
SMTP_HOST = os.getenv('SMTP_HOST', 'smtp.qq.com')
SMTP_PORT = int(os.getenv('SMTP_PORT', '465'))
SMTP_USER = os.getenv('SMTP_USER', '')
SMTP_PASS = os.getenv('SMTP_PASS', '')
SMTP_FROM = os.getenv('SMTP_FROM', '') or SMTP_USER
EMAIL_CODE_TTL = 600
EMAIL_CODE_COOLDOWN = 60
EMAIL_CODE_MAX_TRIES = 5
engine = create_engine(os.getenv('DATABASE_URL', 'sqlite:///./test.db'), pool_pre_ping=True)
Session = sessionmaker(engine, expire_on_commit=False)
cache = Redis.from_url(os.getenv('REDIS_URL', 'redis://localhost:6379/0'), decode_responses=True, socket_timeout=5, socket_connect_timeout=5)
passwords = PasswordHasher()


class Base(DeclarativeBase):
    pass


def uid():
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = 'users'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    password_hash: Mapped[str] = mapped_column(Text)
    session_version: Mapped[int] = mapped_column(Integer, default=1)
    theme: Mapped[str] = mapped_column(String(10), default='system')
    default_account_id: Mapped[str] = mapped_column(String(36), default='', server_default=text("''"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Account(Base):
    __tablename__ = 'gmail_accounts'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey('users.id', ondelete='CASCADE'), index=True)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    provider: Mapped[str] = mapped_column(String(20))
    secret: Mapped[str] = mapped_column(Text)
    sync_state: Mapped[str] = mapped_column(Text, default='{}')
    status: Mapped[str] = mapped_column(String(20), default='connected')
    credential_version: Mapped[int] = mapped_column(Integer, default=1, server_default='1')
    write_revision: Mapped[int] = mapped_column(Integer, default=0, server_default='0')
    accessed_at: Mapped[float] = mapped_column(Float, default=0, server_default='0')


class MailFolder(Base):
    __tablename__ = 'mail_folders'
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    account_id: Mapped[str] = mapped_column(ForeignKey('gmail_accounts.id', ondelete='CASCADE'), index=True)
    folder: Mapped[str] = mapped_column(Text)
    label: Mapped[str] = mapped_column(Text, default='{}')
    checkpoint: Mapped[str] = mapped_column(Text, default='{}')
    version: Mapped[int] = mapped_column(Integer, default=0)
    complete: Mapped[bool] = mapped_column(Boolean, default=False)
    synced_at: Mapped[float] = mapped_column(Float, default=0)


class MailSummary(Base):
    __tablename__ = 'mail_summaries'
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    account_id: Mapped[str] = mapped_column(ForeignKey('gmail_accounts.id', ondelete='CASCADE'), index=True)
    folder_key: Mapped[str] = mapped_column(ForeignKey('mail_folders.key', ondelete='CASCADE'), index=True)
    message_id: Mapped[str] = mapped_column(Text)
    thread_id: Mapped[str] = mapped_column(Text)
    sort_at: Mapped[float] = mapped_column(Float, default=0)
    data: Mapped[str] = mapped_column(Text)
    scan: Mapped[str] = mapped_column(String(36), default='')
    __table_args__ = (Index('ix_summary_thread', 'folder_key', 'thread_id'),)


class MailThread(Base):
    __tablename__ = 'mail_threads'
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    folder_key: Mapped[str] = mapped_column(ForeignKey('mail_folders.key', ondelete='CASCADE'))
    thread_id: Mapped[str] = mapped_column(Text)
    sort_at: Mapped[float] = mapped_column(Float)
    data: Mapped[str] = mapped_column(Text)
    __table_args__ = (Index('ix_threads_page', 'folder_key', 'sort_at', 'key'),)


class SyncJob(Base):
    __tablename__ = 'sync_jobs'
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    account_id: Mapped[str] = mapped_column(ForeignKey('gmail_accounts.id', ondelete='CASCADE'), index=True)
    folder: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(20), default='queued')
    priority: Mapped[int] = mapped_column(Integer, default=0)
    available_at: Mapped[float] = mapped_column(Float, default=0)
    lease_until: Mapped[float] = mapped_column(Float, default=0)
    owner: Mapped[str] = mapped_column(String(36), default='')
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    error: Mapped[str] = mapped_column(String(100), default='')


class ComposeOperation(Base):
    __tablename__ = 'compose_operations'
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    account_id: Mapped[str] = mapped_column(ForeignKey('gmail_accounts.id', ondelete='CASCADE'), index=True)
    version: Mapped[int] = mapped_column(Integer, default=0)
    draft: Mapped[str] = mapped_column(Text, default='{}')
    send_status: Mapped[str] = mapped_column(String(20), default='')
    result: Mapped[str] = mapped_column(Text, default='{}')


def encrypt(data):
    return Fernet(os.environ['TOKEN_ENCRYPTION_KEY']).encrypt(json.dumps(data).encode()).decode()


def decrypt(data):
    return json.loads(Fernet(os.environ['TOKEN_ENCRYPTION_KEY']).decrypt(data.encode()))


def normalize_email(email):
    return email.strip().lower()


def canonical_email(email):
    value = email.strip().lower()
    local, domain = value.rsplit('@', 1)
    if domain in ('gmail.com', 'googlemail.com'):
        return local.split('+', 1)[0].replace('.', '') + '@gmail.com'
    return value


def verify(value, hashed):
    try:
        return passwords.verify(hashed, value)
    except (VerificationError, InvalidHashError):
        return False


CODE_KEY = hashlib.sha256((os.environ.get('TOKEN_ENCRYPTION_KEY', 'hmail-token-key-default') + ':email-code').encode()).digest()


def code_digest(code):
    return hmac.new(CODE_KEY, code.encode('utf-8'), hashlib.sha256).hexdigest()


def store_code(purpose, email, code):
    cache.setex(f'code:{purpose}:{email}', EMAIL_CODE_TTL, code_digest(code))


def consume_code(purpose, email, code):
    key, tries_key = f'code:{purpose}:{email}', f'code-tries:{purpose}:{email}'
    stored = cache.get(key)
    if not stored:
        return False
    tries = cache.incr(tries_key)
    cache.expire(tries_key, EMAIL_CODE_TTL)
    if tries > EMAIL_CODE_MAX_TRIES:
        cache.delete(key)
        return False
    if not hmac.compare_digest(stored, code_digest(code)):
        return False
    cache.delete(key)
    cache.delete(tries_key)
    return True
