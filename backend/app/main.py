import hashlib
import html
import ipaddress
import json
import logging
import os
import re
import secrets
import smtplib
import socket
import imaplib
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Literal
from urllib.parse import quote as urlquote, unquote as urlunquote, urlparse

import httpx
from fastapi import FastAPI, Request, Response, Depends, UploadFile, File
from fastapi.responses import JSONResponse, RedirectResponse, HTMLResponse
from fastapi.exceptions import RequestValidationError
from google_auth_oauthlib.flow import Flow
from google.auth.exceptions import RefreshError
from pydantic import BaseModel, Field, field_validator
from redis.exceptions import RedisError, LockError
from sqlalchemy import select, text, delete, update
from sqlalchemy.exc import IntegrityError
from starlette.concurrency import run_in_threadpool

from .core import *
from .emailer import send_code as send_code_mail, smtp_configured
from .mail import MailError, MAX_ATTACHMENT, build_message, recipients, safe_html, sign_url, verify_url_sig, b64, unb64
from .presets import MAIL_PRESETS
from .providers import GmailApiProvider, ImapSmtpProvider
from .concurrency import admission, slots, imap_client, discard_account
from . import indexing

app = FastAPI(title='Hmail API', version='1.0.0', docs_url=None, redoc_url=None, openapi_url='/api/openapi.json')
logging.basicConfig(level=logging.INFO, format='%(message)s')
log = logging.getLogger('hmail')
UPLOAD_DIR = Path(tempfile.gettempdir()) / 'hmail-uploads'
UPLOAD_DIR.mkdir(mode=0o700, exist_ok=True)
PREFIX = '/api/v1'
DUMMY_HASH = passwords.hash('not-a-real-account-password')


@app.middleware('http')
async def request_context(request, call_next):
    request.state.request_id = secrets.token_hex(8)
    start = time.monotonic()
    if request.method not in ('GET', 'HEAD', 'OPTIONS') and request.headers.get('origin') != PUBLIC_URL:
        return JSONResponse({'code': 'origin', 'message': '请求来源无效', 'requestId': request.state.request_id}, status_code=403)
    response = await call_next(request)
    response.headers['X-Request-ID'] = request.state.request_id
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Cache-Control'] = 'no-store'
    # Paths can contain message identifiers; do not log bodies, queries, credentials or mail content.
    log.info(json.dumps({'requestId': request.state.request_id, 'method': request.method, 'status': response.status_code, 'durationMs': round((time.monotonic() - start) * 1000)}))
    return response


@app.exception_handler(MailError)
async def mail_error(request, exc):
    return JSONResponse({'code': exc.code, 'message': exc.message, 'requestId': request.state.request_id}, status_code=exc.status, headers={'Retry-After': '2'} if exc.status == 429 else None)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    return JSONResponse({'code': 'validation', 'message': '输入格式不正确，请检查必填项及长度', 'requestId': request.state.request_id}, status_code=422)


@app.exception_handler(RedisError)
async def redis_error(request, exc):
    return JSONResponse({'code': 'unavailable', 'message': '会话服务暂时不可用，请稍后重试', 'requestId': request.state.request_id}, status_code=503)


@app.exception_handler(Exception)
async def unexpected_error(request, exc):
    log.error('request_failed requestId=%s type=%s', request.state.request_id, type(exc).__name__)
    return JSONResponse({'code': 'internal', 'message': '服务暂时不可用，请稍后重试', 'requestId': request.state.request_id}, status_code=500)


def limited(request, action, identity='', maximum=8, seconds=900):
    ip = request.headers.get('x-real-ip', request.client.host if request.client else 'local')
    for kind, value in (('ip', ip), ('account', identity)):
        if not value:
            continue
        key = 'limit:' + action + ':' + kind + ':' + hashlib.sha256(value.encode()).hexdigest()
        count = cache.eval("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", 1, key, seconds)
        if count > maximum:
            raise MailError('尝试次数过多，请稍后再试', 'rate_limit', 429)


def current_user(request: Request):
    sid = request.cookies.get('hmail_session', '')
    value = cache.get('session:' + sid) if sid else None
    if not value:
        raise MailError('请先登录', 'unauthorized', 401)
    session = json.loads(value)
    with Session() as db:
        user = db.get(User, session['user'])
    if not user or user.session_version != session['version']:
        raise MailError('登录已失效，请重新登录', 'unauthorized', 401)
    if request.method not in ('GET', 'HEAD', 'OPTIONS') and not secrets.compare_digest(request.headers.get('x-csrf-token', ''), session['csrf']):
        raise MailError('请求验证失败，请刷新页面', 'csrf', 403)
    request.state.session = session
    return user


def public_user(user):
    return {'id': user.id, 'email': user.email, 'theme': user.theme}


def make_session(user, response, old=''):
    if old:
        cache.delete('session:' + old)
    sid, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    cache.setex('session:' + sid, 86400 * 7, json.dumps({'user': user.id, 'version': user.session_version, 'csrf': csrf}))
    response.set_cookie('hmail_session', sid, max_age=86400 * 7, httponly=True, secure=PUBLIC_URL.startswith('https:'), samesite='lax', path='/')
    return {'user': public_user(user), 'csrf': csrf}


class Credentials(BaseModel):
    email: str = Field(min_length=3, max_length=254, pattern=r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
    password: str = Field(min_length=10, max_length=128)

    @field_validator('email')
    @classmethod
    def canonical(cls, value):
        return normalize_email(value)


class EmailCode(BaseModel):
    email: str = Field(min_length=3, max_length=254, pattern=r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
    purpose: Literal['register', 'reset']

    @field_validator('email')
    @classmethod
    def canonical(cls, value):
        return normalize_email(value)


class Register(Credentials):
    code: str = Field(min_length=6, max_length=6, pattern=r'^\d{6}$')


class Reset(Credentials):
    code: str = Field(min_length=6, max_length=6, pattern=r'^\d{6}$')


@app.get(PREFIX + '/health')
def health():
    with engine.connect() as db:
        db.execute(text('SELECT 1'))
    cache.ping()
    return {'status': 'ok'}


@app.get('/api/docs', response_class=HTMLResponse, include_in_schema=False)
def api_docs():
    schema = app.openapi()
    sections = []
    for path, methods in schema['paths'].items():
        for method, spec in methods.items():
            sections.append('<details><summary><b>' + html.escape(method.upper()) + '</b> ' + html.escape(path) + '</summary><pre>' + html.escape(json.dumps(spec, ensure_ascii=False, indent=2)) + '</pre></details>')
    models = html.escape(json.dumps(schema.get('components', {}), ensure_ascii=False, indent=2))
    return '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Hmail API</title><style>body{max-width:1050px;margin:40px auto;padding:0 24px;font:14px/1.8 system-ui;color:#263238}a,b{color:#2563eb}details{border:1px solid #e2e8f0;border-radius:10px;padding:12px;margin:10px 0}summary{cursor:pointer}pre{overflow:auto;background:#f8fafc;padding:16px;font-size:12px}</style><h1>Hmail API</h1><p>本地接口参考 · <a href="/api/openapi.json">下载 OpenAPI JSON</a> · <a href="/">返回邮箱</a></p><p>写请求要求同源 Origin；已登录写请求还需会话 Cookie 和 X-CSRF-Token。点击接口查看参数及返回结构。</p>' + ''.join(sections) + '<details><summary>数据模型 Schemas</summary><pre>' + models + '</pre></details></html>'


@app.get(PREFIX + '/config')
def config():
    return {'oauthEnabled': bool(GOOGLE_ID and GOOGLE_SECRET), 'emailCodeEnabled': smtp_configured(), 'maxAttachmentBytes': MAX_ATTACHMENT, 'mailProviders': MAIL_PRESETS}


@app.post(PREFIX + '/auth/send-code')
def send_code(data: EmailCode, request: Request):
    limited(request, 'send-code', data.email, 5, 3600)
    with Session() as db:
        exists = db.scalar(select(User.id).where(User.email == data.email)) is not None
    if data.purpose == 'register' and exists:
        raise MailError('该邮箱已注册，请直接登录或重置密码', 'conflict', 409)
    if not cache.set(f'code-cooldown:{data.purpose}:{data.email}', '1', nx=True, ex=EMAIL_CODE_COOLDOWN):
        raise MailError('验证码发送过于频繁，请稍后再试', 'rate_limit', 429)
    # 重置密码时账号不存在也返回成功，避免暴露邮箱注册状态。
    if data.purpose == 'reset' and not exists:
        return {'ok': True, 'cooldown': EMAIL_CODE_COOLDOWN}
    code = f'{secrets.randbelow(1000000):06d}'
    store_code(data.purpose, data.email, code)
    try:
        send_code_mail(data.email, code, data.purpose)
    except MailError:
        cache.delete(f'code:{data.purpose}:{data.email}')
        cache.delete(f'code-cooldown:{data.purpose}:{data.email}')
        raise
    return {'ok': True, 'cooldown': EMAIL_CODE_COOLDOWN}


@app.post(PREFIX + '/auth/register')
def register(data: Register, request: Request, response: Response):
    limited(request, 'register', data.email, 10)
    with Session() as db:
        if db.scalar(select(User.id).where(User.email == data.email)):
            raise MailError('该邮箱已被注册', 'conflict', 409)
    if not consume_code('register', data.email, data.code):
        raise MailError('验证码不正确或已过期，请重新获取', 'code_invalid', 422)
    user = User(email=data.email, password_hash=passwords.hash(data.password))
    with Session() as db:
        db.add(user)
        try:
            db.commit()
        except IntegrityError:
            raise MailError('该邮箱已被注册', 'conflict', 409) from None
    return make_session(user, response, request.cookies.get('hmail_session'))


@app.post(PREFIX + '/auth/login')
def login(data: Credentials, request: Request, response: Response):
    limited(request, 'login', data.email)
    with Session() as db:
        user = db.scalar(select(User).where(User.email == data.email))
    if not verify(data.password, user.password_hash if user else DUMMY_HASH) or not user:
        raise MailError('邮箱或密码不正确', 'credentials', 401)
    return make_session(user, response, request.cookies.get('hmail_session'))


@app.post(PREFIX + '/auth/logout')
def logout(request: Request, response: Response, user=Depends(current_user)):
    cache.delete('session:' + request.cookies['hmail_session'])
    response.delete_cookie('hmail_session', path='/')
    return {'ok': True}


@app.post(PREFIX + '/auth/reset')
def reset(data: Reset, request: Request):
    limited(request, 'reset', data.email, 10)
    with Session() as db:
        user = db.scalar(select(User).where(User.email == data.email))
    if not user:
        raise MailError('该邮箱尚未注册', 'not_found', 404)
    if not consume_code('reset', data.email, data.code):
        raise MailError('验证码不正确或已过期，请重新获取', 'code_invalid', 422)
    with Session() as db:
        stored = db.get(User, user.id)
        stored.password_hash = passwords.hash(data.password)
        stored.session_version += 1
        db.commit()
    return {'ok': True}


@app.get(PREFIX + '/me')
def me(request: Request, user=Depends(current_user)):
    return {'user': public_user(user), 'csrf': request.state.session['csrf']}


class Preferences(BaseModel):
    theme: Literal['light', 'dark', 'system']


@app.patch(PREFIX + '/me')
def preferences(data: Preferences, user=Depends(current_user)):
    with Session() as db:
        stored = db.get(User, user.id)
        stored.theme = data.theme
        db.commit()
    return {'ok': True}


class PasswordChange(BaseModel):
    currentPassword: str
    password: str = Field(min_length=10, max_length=128)


@app.post(PREFIX + '/me/password')
def password_change(data: PasswordChange, request: Request, user=Depends(current_user)):
    limited(request, 'password', user.id, 5)
    if not verify(data.currentPassword, user.password_hash):
        raise MailError('当前密码不正确', 'credentials', 400)
    with Session() as db:
        stored = db.get(User, user.id)
        stored.password_hash = passwords.hash(data.password)
        stored.session_version += 1
        db.commit()
    return {'ok': True}


def account_for(user, aid):
    with Session() as db:
        account = db.get(Account, aid)
    if not account or account.user_id != user.id:
        raise MailError('邮箱不存在', 'not_found', 404)
    if cache.set(f'mail-active:{aid}', '1', nx=True, ex=60):
        with Session.begin() as db:
            db.execute(update(Account).where(Account.id == aid).values(accessed_at=time.time()))
    return account


def account_view(account, default_id=''):
    return {'id': account.id, 'email': account.email, 'provider': account.provider, 'status': account.status, 'isDefault': account.id == default_id}


def invalidate(user_id, aid):
    cache.incr(f'mail-version:{aid}')


@contextmanager
def provider_for(user, aid, lane='read'):
    with admission(aid, lane) as lost:
        account = account_for(user, aid)
        try:
            if account.provider == 'oauth':
                from google.oauth2.credentials import Credentials as GoogleCredentials
                from google.auth.transport.requests import Request as GoogleRequest
                secret = decrypt(account.secret)
                if not GoogleCredentials.from_authorized_user_info(secret, SCOPES).valid:
                    with slots([(f'{aid}:credentials', 1)]):
                        account = account_for(user, aid)
                        credentials = GoogleCredentials.from_authorized_user_info(decrypt(account.secret), SCOPES)
                        if not credentials.valid:
                            credentials.refresh(GoogleRequest())
                            with Session() as db:
                                updated = db.execute(update(Account).where(Account.id == aid, Account.credential_version == account.credential_version).values(secret=encrypt(json.loads(credentials.to_json()))))
                                if not updated.rowcount:
                                    raise MailError('邮箱连接已更新，请重试', 'account_changed', 409)
                                db.commit()
                        secret = json.loads(credentials.to_json())
                provider = GmailApiProvider(account.email, secret)
                try:
                    yield provider, account
                finally:
                    provider.close()
            else:
                with imap_client(account, lambda: ImapSmtpProvider(account.email, decrypt(account.secret)), lane) as provider:
                    yield provider, account
                    latest = account_for(user, aid)
                    if latest.credential_version != account.credential_version or lost.is_set():
                        raise MailError('邮箱连接已更新，请重试', 'account_changed', 409)
        except (RefreshError, smtplib.SMTPAuthenticationError):
            with Session() as db:
                db.execute(update(Account).where(Account.id == aid, Account.credential_version == account.credential_version).values(status='reconnect'))
                db.commit()
            raise MailError('邮箱连接已失效，请重新连接', 'reconnect', 401) from None
        except (TimeoutError, OSError, imaplib.IMAP4.abort):
            raise MailError('连接邮箱超时，请稍后重试', 'network', 502) from None


def save_account(user, email, kind, secret):
    canonical = canonical_email(email)
    with Session() as db:
        account = db.scalar(select(Account).where(Account.email == canonical))
        if account and account.user_id != user.id:
            raise MailError('此邮箱已连接其他平台账户', 'conflict', 409)
        if account:
            with slots([(f'{account.id}:write', 1), (f'{account.id}:credentials', 1)]):
                db.refresh(account)
                account.provider, account.secret, account.status, account.sync_state = kind, encrypt(secret), 'connected', '{}'
                account.credential_version += 1
                indexing.clear_account(db, account.id)
                default_id = user.default_account_id
                if not default_id:
                    # 首个连接的邮箱自动成为默认邮箱，用户之后可随时更改。
                    default_id = account.id
                    db.get(User, user.id).default_account_id = default_id
                db.commit()
                invalidate(user.id, account.id)
                discard_account(account.id)
        else:
            account = Account(user_id=user.id, email=canonical, provider=kind, secret=encrypt(secret))
            db.add(account)
            try:
                db.commit()
            except IntegrityError:
                raise MailError('此邮箱已连接，请刷新', 'conflict', 409) from None
            default_id = user.default_account_id
            if not default_id:
                default_id = account.id
                db.get(User, user.id).default_account_id = default_id
                db.commit()
    if indexing.ENABLED:
        indexing.enqueue(account.id, 'INBOX', 20)
    return account_view(account, default_id)


@app.get(PREFIX + '/gmail-accounts')
def accounts(user=Depends(current_user)):
    with Session() as db:
        return [account_view(a, user.default_account_id) for a in db.scalars(select(Account).where(Account.user_id == user.id))]


class DefaultAccount(BaseModel):
    accountId: str = Field(default='', max_length=36)


@app.put(PREFIX + '/me/default-account')
def set_default_account(data: DefaultAccount, user=Depends(current_user)):
    """记住默认邮箱；accountId 传空字符串表示取消默认。"""
    if data.accountId:
        account_for(user, data.accountId)
    with Session() as db:
        stored = db.get(User, user.id)
        stored.default_account_id = data.accountId
        db.commit()
    return {'ok': True, 'defaultAccountId': data.accountId}


HOST_PATTERN = re.compile(r'^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$')


def clean_mailbox_host(value):
    host = value.strip().rstrip('.')
    if not HOST_PATTERN.match(host):
        raise MailError('邮件服务器地址格式不正确，请检查后重试', 'validation', 422)
    return host.lower()


def guard_mailbox_host(host):
    """Resolve the host and refuse cloud metadata / link-local targets."""
    try:
        infos = socket.getaddrinfo(host, None, socket.AF_UNSPEC, socket.SOCK_STREAM)
    except socket.gaierror:
        raise MailError('无法解析邮件服务器地址，请检查后重试', 'dns_error', 502) from None
    for info in infos:
        try:
            ip = ipaddress.ip_address(info[4][0])
        except ValueError:
            continue
        if ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            raise MailError('禁止连接受限的邮件服务器地址', 'ssrf_blocked', 403)


class ImapConnect(BaseModel):
    email: str = Field(min_length=3, max_length=254, pattern=r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
    password: str = Field(min_length=1, max_length=256)
    imapHost: str = Field(min_length=1, max_length=253)
    imapPort: int = Field(ge=1, le=65535)
    imapSecurity: Literal['ssl', 'starttls']
    smtpHost: str = Field(min_length=1, max_length=253)
    smtpPort: int = Field(ge=1, le=65535)
    smtpSecurity: Literal['ssl', 'starttls']

    @field_validator('email')
    @classmethod
    def canonical(cls, value):
        return normalize_email(value)


@app.post(PREFIX + '/gmail-accounts/imap')
def connect_imap(data: ImapConnect, request: Request, user=Depends(current_user)):
    limited(request, 'connect', user.id, 10)
    password = data.password.strip()
    if password.count(' ') == 3 and len(password.replace(' ', '')) == 16:
        # Google 应用专用密码常以 4 位一组显示，去掉分组空格后再使用。
        password = password.replace(' ', '')
    imap_host, smtp_host = clean_mailbox_host(data.imapHost), clean_mailbox_host(data.smtpHost)
    guard_mailbox_host(imap_host)
    guard_mailbox_host(smtp_host)
    secret = {
        'password': password,
        'imap': {'host': imap_host, 'port': data.imapPort, 'security': data.imapSecurity},
        'smtp': {'host': smtp_host, 'port': data.smtpPort, 'security': data.smtpSecurity},
    }
    provider = None
    try:
        provider = ImapSmtpProvider(canonical_email(data.email), secret)
        provider.validate()
    except (imaplib.IMAP4.error, smtplib.SMTPAuthenticationError):
        raise MailError('认证失败，请检查邮箱地址与密码/授权码，并确认邮箱已开启 IMAP 与 SMTP 服务', 'credentials', 400) from None
    except smtplib.SMTPException:
        raise MailError('无法连接发信服务器，请检查 SMTP 主机、端口与加密方式', 'network', 502) from None
    except (OSError, TimeoutError):
        raise MailError('无法连接邮件服务器，请检查网络与服务器配置', 'network', 502) from None
    finally:
        if provider:
            provider.close()
    return save_account(user, data.email, 'imap', secret)


def oauth_flow(state=None):
    if not GOOGLE_ID or not GOOGLE_SECRET:
        raise MailError('尚未配置 Google OAuth，可使用 IMAP＋SMTP 连接', 'not_configured', 503)
    return Flow.from_client_config({'web': {'client_id': GOOGLE_ID, 'client_secret': GOOGLE_SECRET, 'auth_uri': 'https://accounts.google.com/o/oauth2/auth', 'token_uri': 'https://oauth2.googleapis.com/token'}}, scopes=SCOPES, redirect_uri=REDIRECT_URI, state=state)


@app.post(PREFIX + '/gmail-accounts/oauth/start')
def oauth_start(request: Request, user=Depends(current_user)):
    state = secrets.token_urlsafe(32)
    flow = oauth_flow(state)
    url, _ = flow.authorization_url(access_type='offline', prompt='consent')
    cache.setex('oauth:' + state, 600, json.dumps({'user': user.id, 'session': request.cookies['hmail_session']}))
    return {'url': url}


@app.post(PREFIX + '/gmail-accounts/oauth/mobile/start')
def mobile_oauth_start(request: Request, user=Depends(current_user)):
    limited(request, 'mobile-oauth', user.id, 10)
    state, ticket = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    flow = oauth_flow(state)
    url, _ = flow.authorization_url(access_type='offline', prompt='consent')
    record = {'user': user.id, 'session': request.cookies['hmail_session'],
              'mobile': True, 'ticket': ticket, 'status': 'waiting'}
    cache.setex('oauth:' + state, 600, json.dumps(record))
    cache.setex('mobile-oauth:' + ticket, 600, json.dumps(record))
    return {'url': url, 'ticket': ticket, 'expiresIn': 600}


@app.get(PREFIX + '/gmail-accounts/oauth/mobile/status')
def mobile_oauth_status(request: Request, user=Depends(current_user)):
    ticket = request.headers.get('x-oauth-ticket', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]{43}', ticket):
        raise MailError('连接请求无效，请重试', 'oauth_state', 400)
    raw = cache.get('mobile-oauth:' + ticket)
    if not raw:
        return {'status': 'expired'}
    record = json.loads(raw)
    if record['user'] != user.id or not secrets.compare_digest(record['session'], request.cookies['hmail_session']):
        raise MailError('连接请求无效，请重试', 'oauth_state', 403)
    return {'status': record['status']}


def mobile_oauth_finish(record, status):
    record['status'] = status
    cache.setex('mobile-oauth:' + record['ticket'], 600, json.dumps(record))
    title = {'success': '邮箱已连接', 'cancelled': '已取消连接', 'failed': '连接失败，请重试'}[status]
    return HTMLResponse('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
                        '<meta name="viewport" content="width=device-width,initial-scale=1">'
                        '<title>Hmail</title><style>body{background:#f6f8fc;color:#1e293b;'
                        'font:16px system-ui;text-align:center;padding:20vh 24px}'
                        'h1{font-size:24px}p{color:#64748b}</style><h1>' + title +
                        '</h1><p>返回 Hmail</p></html>', headers={
                            'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'",
                            'Referrer-Policy': 'no-referrer'})


@app.get(PREFIX + '/gmail-accounts/oauth/callback')
def oauth_callback(request: Request, state: str = '', code: str = '', error: str = ''):
    pending = cache.get('oauth:' + state)
    if not pending:
        raise MailError('授权请求已过期，请重新连接', 'oauth_state', 400)
    # Authenticate the web session before consuming the one-time state.
    web_user = None if json.loads(pending).get('mobile') else current_user(request)
    value = cache.getdel('oauth:' + state)
    if not value:
        raise MailError('授权请求已过期，请重新连接', 'oauth_state', 400)
    value = json.loads(value)
    if value.get('mobile'):
        # Browser cookies are unrelated to the native session. Bind exclusively to
        # the single-use server-generated state and revalidate the initiating session.
        raw_session = cache.get('session:' + value['session'])
        session = json.loads(raw_session) if raw_session else None
        with Session() as db:
            user = db.get(User, value['user'])
        if not user or not session or session.get('user') != user.id or session.get('version') != user.session_version:
            return mobile_oauth_finish(value, 'failed')
        if error or not code:
            return mobile_oauth_finish(value, 'cancelled')
        provider = None
        try:
            flow = oauth_flow(state)
            flow.fetch_token(code=code)
            secret = json.loads(flow.credentials.to_json())
            if not secret.get('refresh_token'):
                return mobile_oauth_finish(value, 'failed')
            provider = GmailApiProvider('', secret)
            email = provider.profile()['emailAddress']
            # Password changes or logout during consent/token exchange invalidate it.
            current = cache.get('session:' + value['session'])
            with Session() as db:
                latest = db.get(User, value['user'])
            if not current or not latest or latest.session_version != session['version']:
                return mobile_oauth_finish(value, 'failed')
            save_account(latest, email, 'oauth', secret)
            return mobile_oauth_finish(value, 'success')
        except Exception:
            log.warning('mobile_oauth_failed requestId=%s', request.state.request_id)
            return mobile_oauth_finish(value, 'failed')
        finally:
            if provider:
                provider.close()
    user = web_user
    if value['user'] != user.id or not secrets.compare_digest(value['session'], request.cookies.get('hmail_session', '')):
        raise MailError('授权请求与当前会话不匹配', 'oauth_state', 400)
    if error or not code:
        return RedirectResponse('/?connection=cancelled', status_code=303)
    flow = oauth_flow(state)
    flow.fetch_token(code=code)
    secret = json.loads(flow.credentials.to_json())
    if not secret.get('refresh_token'):
        raise MailError('Google 未授予离线访问，请撤销旧授权后重试', 'oauth_refresh', 400)
    provider = GmailApiProvider('', secret)
    try:
        email = provider.profile()['emailAddress']
    finally:
        provider.close()
    save_account(user, email, 'oauth', secret)
    return RedirectResponse('/?connection=success', status_code=303)


@app.delete(PREFIX + '/gmail-accounts/{aid}')
def disconnect(aid: str, user=Depends(current_user)):
    account_for(user, aid)
    with slots([(f'{aid}:write', 1), (f'{aid}:credentials', 1)]):
        with Session() as db:
            indexing.clear_account(db, aid)
            db.execute(delete(ComposeOperation).where(ComposeOperation.account_id == aid))
            db.execute(delete(Account).where(Account.id == aid, Account.user_id == user.id))
            if user.default_account_id == aid:
                store = db.get(User, user.id)
                store.default_account_id = ''
            db.commit()
        invalidate(user.id, aid)
        discard_account(aid)
    return {'ok': True}


def cached_call(user, aid, suffix, ttl, fn):
    account = account_for(user, aid)
    version = 'content' if suffix.startswith('message:') else cache.get(f'mail-version:{aid}') or '0'
    key = f'mail:{user.id}:{aid}:{account.credential_version}:{version}:' + hashlib.sha256(suffix.encode()).hexdigest()
    cached = cache.get(key)
    if cached:
        return indexing.overlay_labels(aid, json.loads(cached))
    with slots([('fetch:' + key, 1)], wait=35):
        cached = cache.get(key)
        if cached:
            return indexing.overlay_labels(aid, json.loads(cached))
        with provider_for(user, aid) as (provider, _):
            result = fn(provider)
            serialized = json.dumps(result)
            if len(serialized.encode()) <= 1024 * 1024:
                cache.setex(key, ttl, serialized)
    return indexing.overlay_labels(aid, result)


@app.get(PREFIX + '/gmail-accounts/{aid}/threads')
def threads(aid: str, folder: str = 'INBOX', q: str = '', cursor: str = '', user=Depends(current_user)):
    if len(q) > 2000 or len(cursor) > 2000:
        raise MailError('搜索参数过长', 'validation', 422)
    account_for(user, aid)
    if indexing.ENABLED and not q:
        return indexing.list_threads(aid, folder, cursor)
    return cached_call(user, aid, json.dumps(['list', folder, q, cursor]), 60, lambda p: p.list(folder, q, cursor))


def check_ip_ssrf(ip_str: str):
    ip = ipaddress.ip_address(ip_str)
    if (ip.is_private or ip.is_loopback or ip.is_link_local 
        or ip.is_multicast or ip.is_reserved or ip.is_unspecified):
        raise MailError('禁止访问内网或受限 IP 地址', 'ssrf_blocked', 403)


def validate_proxy_target_url(raw_url: str) -> tuple[str, str, int]:
    target = raw_url.strip()
    if target.startswith('//'):
        target = 'https:' + target
    parsed = urlparse(target)
    if parsed.scheme not in ('http', 'https'):
        raise MailError('不支持的图片协议', 'invalid_url', 400)
    hostname = parsed.hostname
    if not hostname:
        raise MailError('缺少图片主机名', 'invalid_url', 400)
    clean_host = hostname.lower().strip('.')
    if clean_host in ('localhost', '127.0.0.1', '::1', '0.0.0.0', 'host.docker.internal'):
        raise MailError('禁止访问本地主机', 'ssrf_blocked', 403)
    if any(clean_host.endswith(suf) for suf in ('.local', '.internal', '.lan', '.home', '.corp', '.onion')):
        raise MailError('禁止访问局域网域名', 'ssrf_blocked', 403)
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    if port not in (80, 443, 8080, 8443):
        raise MailError('不支持的图片端口', 'ssrf_blocked', 403)
    try:
        addr_info = socket.getaddrinfo(hostname, port, socket.AF_UNSPEC, socket.SOCK_STREAM)
    except socket.gaierror:
        raise MailError('图片域名解析失败', 'dns_error', 502)
    for *_, sockaddr in addr_info:
        check_ip_ssrf(sockaddr[0])
    return target, hostname, port


@app.get(PREFIX + '/proxy/image')
async def proxy_image(url: str, sig: str = '', request: Request = None):
    if not url:
        raise MailError('缺少 url 参数', 'validation', 422)

    # 1. Signature or session auth
    has_valid_sig = verify_url_sig(url, sig)
    if not has_valid_sig:
        sid = request.cookies.get('hmail_session', '') if request else ''
        session_val = cache.get('session:' + sid) if sid else None
        if not session_val:
            raise MailError('未授权访问图片代理', 'unauthorized', 401)

    # 2. SSRF check
    target_url, _, _ = validate_proxy_target_url(url)

    # 3. Redis cache lookup
    cache_key = 'imgproxy:' + hashlib.sha256(target_url.encode('utf-8')).hexdigest()
    try:
        cached = cache.get(cache_key)
        if cached:
            cached_data = json.loads(cached)
            return Response(
                content=unb64(cached_data['data']),
                media_type=cached_data['type'],
                headers={
                    'Cache-Control': 'public, max-age=86400, immutable',
                    'X-Content-Type-Options': 'nosniff',
                    'Content-Security-Policy': "default-src 'none'",
                }
            )
    except Exception:
        pass

    # 4. Fetch image securely
    current_url = target_url
    client_headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
    }
    final_resp = None

    async with httpx.AsyncClient(timeout=8.0, follow_redirects=False, verify=True) as client:
        for _ in range(4):
            try:
                resp = await client.get(current_url, headers=client_headers)
            except (httpx.RequestError, httpx.TimeoutException) as exc:
                raise MailError(f'获取远程图片失败: {exc}', 'fetch_failed', 502)

            if resp.is_redirect:
                loc = resp.headers.get('location')
                if not loc:
                    raise MailError('重定向缺少 Location', 'fetch_failed', 502)
                resolved_loc = str(httpx.URL(current_url).join(loc))
                target_url_red, _, _ = validate_proxy_target_url(resolved_loc)
                current_url = target_url_red
                continue

            final_resp = resp
            break

    if not final_resp or final_resp.status_code != 200:
        status_code = final_resp.status_code if final_resp else 502
        raise MailError(f'远程图片返回状态异常 ({status_code})', 'fetch_failed', status_code if status_code in (404, 403) else 502)

    content = final_resp.content
    if len(content) > 15 * 1024 * 1024:
        raise MailError('图片超出大小限制 (最大 15MB)', 'payload_too_large', 413)

    raw_ct = final_resp.headers.get('content-type', '').split(';')[0].strip().lower()
    if raw_ct.startswith('image/'):
        content_type = raw_ct
    elif content.startswith(b'\x89PNG\r\n\x1a\n'):
        content_type = 'image/png'
    elif content.startswith(b'\xff\xd8\xff'):
        content_type = 'image/jpeg'
    elif content.startswith(b'GIF8'):
        content_type = 'image/gif'
    elif content.startswith(b'RIFF') and len(content) > 12 and content[8:12] == b'WEBP':
        content_type = 'image/webp'
    elif b'<svg' in content[:512].lower():
        content_type = 'image/svg+xml'
    else:
        content_type = raw_ct or 'image/jpeg'

    if len(content) <= 2 * 1024 * 1024:
        try:
            cache.setex(cache_key, 86400, json.dumps({'type': content_type, 'data': b64(content)}))
        except Exception:
            pass

    return Response(
        content=content,
        media_type=content_type,
        headers={
            'Cache-Control': 'public, max-age=86400, immutable',
            'X-Content-Type-Options': 'nosniff',
            'Content-Security-Policy': "default-src 'none'",
        }
    )


@app.get(PREFIX + '/gmail-accounts/{aid}/threads/{tid}')
def thread(aid: str, tid: str, remote: bool = True, user=Depends(current_user)):
    result = cached_call(user, aid, 'thread:' + tid, 300, lambda p: p.thread(tid))
    return [{**m, 'html': safe_html(m['html'], remote=remote, aid=aid, mid=m['id'], attachments=m.get('attachments')) if m.get('html') else ''} for m in result]


@app.get(PREFIX + '/gmail-accounts/{aid}/messages/{mid}')
def message(aid: str, mid: str, remote: bool = True, user=Depends(current_user)):
    result = cached_call(user, aid, 'message:' + mid, 300, lambda p: p.message(mid))
    return {**result, 'html': safe_html(result['html'], remote=remote, aid=aid, mid=mid, attachments=result.get('attachments')) if result.get('html') else ''}


@app.get(PREFIX + '/gmail-accounts/{aid}/messages/{mid}/attachments/{part}')
def attachment(aid: str, mid: str, part: str, request: Request, sig: str = ''):
    has_valid_sig = verify_url_sig(f'{aid}:{mid}:{part}', sig)
    if not has_valid_sig:
        user = current_user(request)
    else:
        with Session() as db:
            acc = db.get(Account, aid)
            if not acc:
                raise MailError('邮箱账户不存在', 'not_found', 404)
            user = db.get(User, acc.user_id)
            if not user:
                raise MailError('用户不存在', 'not_found', 404)

    with provider_for(user, aid) as (provider, _):
        content, name, content_type = provider.attachment(mid, part)

    media_type = content_type or 'application/octet-stream'
    is_image = media_type.startswith('image/')
    disposition = 'inline' if is_image else 'attachment'
    return Response(
        content,
        media_type=media_type,
        headers={
            'Content-Disposition': f"{disposition}; filename*=UTF-8''" + urlquote(name, safe=''),
            'Cache-Control': 'private, max-age=86400',
            'X-Content-Type-Options': 'nosniff',
        }
    )


@app.get(PREFIX + '/gmail-accounts/{aid}/labels')
def labels(aid: str, user=Depends(current_user)):
    account_for(user, aid)
    if indexing.ENABLED:
        result = indexing.labels(aid)
        if not result:
            indexing.enqueue(aid, 'INBOX', 20, True)
        return result
    return cached_call(user, aid, 'labels', 60, lambda p: p.labels())


class LabelAction(BaseModel):
    action: Literal['create', 'rename', 'delete']
    id: str = ''
    name: str = Field(default='', max_length=200)


@app.post(PREFIX + '/gmail-accounts/{aid}/labels')
def label_action(aid: str, data: LabelAction, user=Depends(current_user)):
    if data.action != 'delete' and not data.name.strip():
        raise MailError('标签名称不能为空', 'validation', 422)
    with provider_for(user, aid, 'write') as (provider, _):
        if data.action != 'create' and not any(l['id'] == data.id and l.get('type') == 'user' for l in provider.labels()):
            raise MailError('仅能修改自定义标签', 'validation', 422)
        provider.label(data.action, data.id, data.name.strip())
        indexing.fence_write(aid)
        invalidate(user.id, aid)
        with Session.begin() as db:
            for label in provider.labels():
                indexing.ensure_folder(db, aid, label['id'], label)
    return {'ok': True}


class Modify(BaseModel):
    ids: list[str] = Field(default_factory=list, max_length=100)
    threadIds: list[str] = Field(default_factory=list, max_length=100)
    add: list[str] = Field(default_factory=list, max_length=20)
    remove: list[str] = Field(default_factory=list, max_length=20)
    action: Literal['labels', 'trash', 'untrash'] = 'labels'


@app.post(PREFIX + '/gmail-accounts/{aid}/messages/modify')
def modify(aid: str, data: Modify, user=Depends(current_user)):
    if not data.ids and not data.threadIds:
        raise MailError('请选择邮件', 'validation', 422)
    results = []
    for identifier in data.threadIds or data.ids:
        try:
            with provider_for(user, aid, 'write') as (provider, account):
                indexing.fence_write(aid)
                if data.threadIds:
                    provider.thread_modify([identifier], data.add, data.remove, data.action)
                elif data.action == 'labels':
                    provider.modify([identifier], data.add, data.remove)
                else:
                    provider.move(identifier, data.action == 'trash')
                indexing.update_after_write(aid, [] if data.threadIds else [identifier], [identifier] if data.threadIds else [], data.add, data.remove, data.action, account.provider == 'imap')
                invalidate(user.id, aid)
            results.append({'id': identifier, 'ok': True})
        except MailError as exc:
            results.append({'id': identifier, 'ok': False, 'code': exc.code, 'message': exc.message})
    if indexing.ENABLED:
        indexing.enqueue(aid, 'INBOX', 10)
        for folder in set(data.add + data.remove + (['TRASH'] if data.action in ('trash', 'untrash') else [])) - {'UNREAD', 'STARRED'}:
            indexing.enqueue(aid, folder, 10)
    return {'ok': all(r['ok'] for r in results), 'results': results}


def cleanup_uploads():
    for path in UPLOAD_DIR.iterdir():
        if path.is_file() and time.time() - path.stat().st_mtime > 86400:
            path.unlink(missing_ok=True)


@app.post(PREFIX + '/gmail-accounts/{aid}/attachments')
def upload(aid: str, request: Request, file: UploadFile = File(...), user=Depends(current_user)):
    account_for(user, aid)
    limited(request, 'upload', user.id, 100, 3600)
    cleanup_uploads()
    identifier = secrets.token_hex(24)
    path = UPLOAD_DIR / identifier
    count = 0
    try:
        with path.open('xb') as stream:
            while chunk := file.file.read(65536):
                count += len(chunk)
                if count > MAX_ATTACHMENT:
                    raise MailError('附件超过 18 MiB 限制', 'too_large', 413)
                stream.write(chunk)
        meta = {'user': user.id, 'account': aid, 'name': Path((file.filename or 'attachment').replace('\\', '/')).name, 'type': file.content_type, 'size': count}
        cache.setex('upload:' + identifier, 86400, json.dumps(meta))
    except Exception:
        path.unlink(missing_ok=True)
        raise
    return {'id': identifier, 'name': meta['name'], 'size': count}


class AttachmentRef(BaseModel):
    id: str
    messageId: str | None = None


class Compose(BaseModel):
    to: str = Field(default='', max_length=4000)
    cc: str = Field(default='', max_length=4000)
    bcc: str = Field(default='', max_length=4000)
    subject: str = Field(default='', max_length=998)
    text: str = Field(default='', max_length=500000)
    inReplyTo: str = Field(default='', max_length=998)
    references: str = Field(default='', max_length=8000)
    threadId: str | None = None
    draftId: str | None = None
    composeId: str = Field(min_length=8, max_length=100, pattern=r'^[A-Za-z0-9_-]+$')
    version: int = Field(default=1, ge=1)
    attachments: list[AttachmentRef] = Field(default_factory=list, max_length=30)


def materialize(data, user, aid, provider, email):
    attached, size = [], 0
    for ref in data.attachments:
        if ref.messageId:
            content, name, kind = provider.attachment(ref.messageId, ref.id)
        else:
            value = cache.get('upload:' + ref.id)
            meta = json.loads(value) if value else None
            if not meta or meta['user'] != user.id or meta['account'] != aid:
                raise MailError('附件已过期，请重新上传', 'attachment_expired', 400)
            path = UPLOAD_DIR / ref.id
            if not path.is_file():
                raise MailError('临时附件已清理，请重新上传', 'attachment_expired', 400)
            content, name, kind = path.read_bytes(), meta['name'], meta['type']
        size += len(content)
        if size > MAX_ATTACHMENT:
            raise MailError('附件总大小超过 18 MiB', 'too_large', 413)
        attached.append((name, content, kind))
    return build_message(email, data.model_dump(), attached)


@app.get(PREFIX + '/gmail-accounts/{aid}/drafts')
def drafts(aid: str, user=Depends(current_user)):
    with provider_for(user, aid) as (provider, _):
        return provider.drafts()


@app.get(PREFIX + '/gmail-accounts/{aid}/drafts/{did}')
def draft_get(aid: str, did: str, user=Depends(current_user)):
    with provider_for(user, aid) as (provider, _):
        result = provider.draft_get(did)
        result['html'] = ''
        return result


@app.put(PREFIX + '/gmail-accounts/{aid}/drafts')
def draft_save(aid: str, data: Compose, user=Depends(current_user)):
    with provider_for(user, aid, 'write') as (provider, account):
        key = indexing.key(aid, data.composeId)
        with Session() as db:
            operation = db.get(ComposeOperation, key)
            if operation and operation.send_status:
                raise MailError('此写信会话已经发送或正在确认结果', 'send_uncertain', 409)
            previous = json.loads(operation.draft) if operation else {}
            if previous.get('pending'):
                raise MailError('草稿保存结果待确认，请重新打开草稿箱', 'draft_uncertain', 409)
        if previous.get('version', 0) >= data.version:
            return previous
        indexing.fence_write(aid)
        msg = materialize(data, user, aid, provider, account.email)
        with Session.begin() as db:
            operation = db.get(ComposeOperation, key)
            if not operation:
                operation = ComposeOperation(key=key, account_id=aid)
                db.add(operation)
            operation.draft = json.dumps({**previous, 'pending': True})
        did = provider.draft_save(msg, previous.get('id') or data.draftId, data.threadId)
        saved = provider.draft_get(did)
        result = {'id': did, 'version': data.version, 'attachments': [{**a, 'messageId': saved['id']} for a in saved['attachments']]}
        with Session.begin() as db:
            operation = db.get(ComposeOperation, key)
            if not operation:
                operation = ComposeOperation(key=key, account_id=aid)
                db.add(operation)
            operation.version, operation.draft = data.version, json.dumps(result)
        invalidate(user.id, aid)
        if indexing.ENABLED:
            indexing.enqueue(aid, 'DRAFT', 20)
        return result


@app.delete(PREFIX + '/gmail-accounts/{aid}/drafts/{did}')
def draft_delete(aid: str, did: str, user=Depends(current_user)):
    with provider_for(user, aid, 'write') as (provider, _):
        indexing.fence_write(aid)
        provider.draft_delete(did)
        with Session.begin() as db:
            for operation in db.scalars(select(ComposeOperation).where(ComposeOperation.account_id == aid)):
                if json.loads(operation.draft).get('id') == did:
                    operation.send_status = 'discarded'
        indexing.update_after_write(aid, [did], [], [], ['DRAFT'], 'labels')
        invalidate(user.id, aid)
    return {'ok': True}


@app.post(PREFIX + '/gmail-accounts/{aid}/send')
def send(aid: str, data: Compose, request: Request, user=Depends(current_user)):
    limited(request, 'send', user.id, 30, 3600)
    envelope = recipients(data.model_dump())
    with provider_for(user, aid, 'write') as (provider, account):
        key = indexing.key(aid, data.composeId)
        with Session() as db:
            operation = db.get(ComposeOperation, key)
            if operation and operation.send_status:
                if operation.send_status == 'sent':
                    return json.loads(operation.result)
                raise MailError('此邮件的发送结果尚不明确，请检查已发送，勿重复发送', 'send_uncertain', 409)
        msg = materialize(data, user, aid, provider, account.email)
        # Commit before contacting SMTP/Gmail. A crash can never make this retryable.
        with Session.begin() as db:
            operation = db.get(ComposeOperation, key)
            if not operation:
                operation = ComposeOperation(key=key, account_id=aid)
                db.add(operation)
            operation.send_status = 'pending'
        indexing.fence_write(aid)
        try:
            result = provider.send(msg, envelope, data.threadId)
        except Exception:
            raise MailError('发送结果尚不明确，请检查已发送后再决定是否重新撰写', 'send_uncertain', 409) from None
        response = {'status': 'sent', 'result': result}
        if result.get('warning'):
            response['warning'] = result['warning']
        with Session.begin() as db:
            operation = db.get(ComposeOperation, key)
            operation.send_status, operation.result = 'sent', json.dumps(response)
        did = data.draftId
        draft_state = json.loads(operation.draft)
        if draft_state:
            did = draft_state['id']
        if did:
            try:
                provider.draft_delete(did)
            except Exception:
                response['warning'] = '邮件已发送，但旧草稿未清理，请手动检查草稿箱'
        for ref in data.attachments:
            if not ref.messageId:
                cache.delete('upload:' + ref.id)
                (UPLOAD_DIR / ref.id).unlink(missing_ok=True)
        invalidate(user.id, aid)
        with Session.begin() as db:
            operation = db.get(ComposeOperation, key)
            operation.result = json.dumps(response)
        if indexing.ENABLED:
            indexing.enqueue(aid, 'SENT', 20)
            indexing.enqueue(aid, 'DRAFT', 20)
        return response


@app.post(PREFIX + '/gmail-accounts/{aid}/sync-jobs', status_code=202)
def sync_job(aid: str, folder: str = 'INBOX', user=Depends(current_user)):
    account_for(user, aid)
    if not indexing.JOBS_ENABLED:
        raise MailError('后台同步接口未启用', 'unsupported', 404)
    return indexing.enqueue(aid, folder, 20, True)


@app.get(PREFIX + '/gmail-accounts/{aid}/sync-status')
def sync_status(aid: str, folder: str = 'INBOX', user=Depends(current_user)):
    account_for(user, aid)
    return indexing.state(aid, folder)


@app.post(PREFIX + '/gmail-accounts/{aid}/sync')
def sync(aid: str, folder: str = 'INBOX', user=Depends(current_user)):
    account_for(user, aid)
    if indexing.ENABLED:
        previous = indexing.state(aid, folder)['indexVersion']
        indexing.enqueue(aid, folder, 20, True)
        deadline = time.monotonic() + 55
        while time.monotonic() < deadline:
            current = indexing.state(aid, folder)
            if current['status'] == 'completed':
                return {'changed': previous != current['indexVersion'], 'reset': False}
            if current['status'] in ('failed', 'retry'):
                raise MailError('同步暂未完成，请稍后重试', current['error'] or 'sync_failed', 502)
            time.sleep(.25)
        raise MailError('同步仍在后台继续，请稍后查看', 'sync_timeout', 504)
    cleanup_uploads()
    with provider_for(user, aid, 'sync') as (provider, account):
        state, changed, reset = provider.sync(json.loads(account.sync_state), folder)
        with Session() as db:
            stored = db.get(Account, aid)
            stored.sync_state, stored.status = json.dumps(state), 'connected'
            db.commit()
        if changed:
            invalidate(user.id, aid)
    return {'changed': changed, 'reset': reset}
