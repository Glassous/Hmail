import json
import os
import unicodedata
import uuid
from datetime import datetime, timezone

from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, InvalidHashError
from cryptography.fernet import Fernet
from redis import Redis
from sqlalchemy import create_engine, String, Text, Integer, ForeignKey, DateTime
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

PUBLIC_URL = os.getenv('PUBLIC_URL', 'http://localhost:5173').rstrip('/')
GOOGLE_ID = os.getenv('GOOGLE_CLIENT_ID', '')
GOOGLE_SECRET = os.getenv('GOOGLE_CLIENT_SECRET', '')
REDIRECT_URI = PUBLIC_URL + '/api/v1/gmail-accounts/oauth/callback'
SCOPES = ['https://www.googleapis.com/auth/gmail.modify']
QUESTIONS = ['你小时候最喜欢的书是什么？', '你自定义的秘密短语是什么？', '你第一次旅行的目的地是哪里？', '你最喜欢的虚构角色是谁？']
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
    username: Mapped[str] = mapped_column(String(40), unique=True)
    password_hash: Mapped[str] = mapped_column(Text)
    question: Mapped[str] = mapped_column(String(200))
    answer_hash: Mapped[str] = mapped_column(Text)
    session_version: Mapped[int] = mapped_column(Integer, default=1)
    theme: Mapped[str] = mapped_column(String(10), default='system')
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


def encrypt(data):
    return Fernet(os.environ['TOKEN_ENCRYPTION_KEY']).encrypt(json.dumps(data).encode()).decode()


def decrypt(data):
    return json.loads(Fernet(os.environ['TOKEN_ENCRYPTION_KEY']).decrypt(data.encode()))


def normalize_answer(answer):
    return unicodedata.normalize('NFKC', answer.strip())


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
