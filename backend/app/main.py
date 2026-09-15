import hashlib
import html
import json
import logging
import os
import secrets
import smtplib
import imaplib
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Literal
from urllib.parse import quote as urlquote

from fastapi import FastAPI, Request, Response, Depends, UploadFile, File
from fastapi.responses import JSONResponse, RedirectResponse, HTMLResponse
from fastapi.exceptions import RequestValidationError
from google_auth_oauthlib.flow import Flow
from google.auth.exceptions import RefreshError
from pydantic import BaseModel, Field, field_validator
from redis.exceptions import RedisError, LockError
from sqlalchemy import select, text, delete
from sqlalchemy.exc import IntegrityError
from starlette.concurrency import run_in_threadpool

from .core import *
from .mail import MailError, MAX_ATTACHMENT, build_message, recipients, safe_html
from .providers import GmailApiProvider, GmailImapSmtpProvider

app = FastAPI(title='FiaGmail API', version='1.0.0', docs_url=None, redoc_url=None, openapi_url='/api/openapi.json')
logging.basicConfig(level=logging.INFO, format='%(message)s')
log = logging.getLogger('fiagmail')
UPLOAD_DIR = Path(tempfile.gettempdir()) / 'fiagmail-uploads'
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
    return JSONResponse({'code': exc.code, 'message': exc.message, 'requestId': request.state.request_id}, status_code=exc.status)


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
    sid = request.cookies.get('fia_session', '')
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
    return {'id': user.id, 'username': user.username, 'theme': user.theme}


def make_session(user, response, old=''):
    if old:
        cache.delete('session:' + old)
    sid, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    cache.setex('session:' + sid, 86400 * 7, json.dumps({'user': user.id, 'version': user.session_version, 'csrf': csrf}))
    response.set_cookie('fia_session', sid, max_age=86400 * 7, httponly=True, secure=PUBLIC_URL.startswith('https:'), samesite='lax', path='/')
    return {'user': public_user(user), 'csrf': csrf}


class Login(BaseModel):
    username: str = Field(min_length=3, max_length=40, pattern=r'^[a-zA-Z0-9_-]+$')
    password: str = Field(min_length=10, max_length=128)

    @field_validator('username')
    @classmethod
    def canonical(cls, value):
        return value.lower()


class Register(Login):
    question: str
    answer: str = Field(min_length=2, max_length=200)


class Recovery(BaseModel):
    username: str = Field(min_length=3, max_length=40)
    answer: str = Field(min_length=2, max_length=200)


class Reset(BaseModel):
    token: str = Field(min_length=20, max_length=100)
    password: str = Field(min_length=10, max_length=128)


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
    return '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FiaGmail API</title><style>body{max-width:1050px;margin:40px auto;padding:0 24px;font:14px/1.8 system-ui;color:#263238}a,b{color:#2563eb}details{border:1px solid #e2e8f0;border-radius:10px;padding:12px;margin:10px 0}summary{cursor:pointer}pre{overflow:auto;background:#f8fafc;padding:16px;font-size:12px}</style><h1>FiaGmail API</h1><p>本地接口参考 · <a href="/api/openapi.json">下载 OpenAPI JSON</a> · <a href="/">返回邮箱</a></p><p>写请求要求同源 Origin；已登录写请求还需会话 Cookie 和 X-CSRF-Token。点击接口查看参数及返回结构。</p>' + ''.join(sections) + '<details><summary>数据模型 Schemas</summary><pre>' + models + '</pre></details></html>'


@app.get(PREFIX + '/config')
def config():
    return {'oauthEnabled': bool(GOOGLE_ID and GOOGLE_SECRET), 'questions': QUESTIONS, 'maxAttachmentBytes': MAX_ATTACHMENT}


@app.post(PREFIX + '/auth/register')
def register(data: Register, request: Request, response: Response):
    limited(request, 'register', data.username, 10)
    if data.question not in QUESTIONS or len(normalize_answer(data.answer)) < 2:
        raise MailError('请选择密保问题并填写至少两个字符的答案', 'validation', 422)
    user = User(username=data.username, password_hash=passwords.hash(data.password), question=data.question, answer_hash=passwords.hash(normalize_answer(data.answer)))
    with Session() as db:
        db.add(user)
        try:
            db.commit()
        except IntegrityError:
            raise MailError('用户名已被使用', 'conflict', 409) from None
    return make_session(user, response, request.cookies.get('fia_session'))


@app.post(PREFIX + '/auth/login')
def login(data: Login, request: Request, response: Response):
    limited(request, 'login', data.username)
    with Session() as db:
        user = db.scalar(select(User).where(User.username == data.username))
    if not verify(data.password, user.password_hash if user else DUMMY_HASH) or not user:
        raise MailError('用户名或密码不正确', 'credentials', 401)
    return make_session(user, response, request.cookies.get('fia_session'))


@app.post(PREFIX + '/auth/logout')
def logout(request: Request, response: Response, user=Depends(current_user)):
    cache.delete('session:' + request.cookies['fia_session'])
    response.delete_cookie('fia_session', path='/')
    return {'ok': True}


@app.get(PREFIX + '/auth/recovery-question')
def recovery_question(username: str, request: Request):
    limited(request, 'question', username.lower(), 10)
    with Session() as db:
        user = db.scalar(select(User).where(User.username == username.lower()))
    # A stable decoy avoids directly reporting whether an account exists.
    return {'question': user.question if user else QUESTIONS[int(hashlib.sha256(username.lower().encode()).hexdigest(), 16) % len(QUESTIONS)]}


@app.post(PREFIX + '/auth/recover')
def recover(data: Recovery, request: Request):
    limited(request, 'recover', data.username.lower(), 5, 3600)
    with Session() as db:
        user = db.scalar(select(User).where(User.username == data.username.lower()))
    if not verify(normalize_answer(data.answer), user.answer_hash if user else DUMMY_HASH) or not user:
        raise MailError('账户或密保答案不正确', 'credentials', 400)
    token = secrets.token_urlsafe(32)
    cache.setex('reset:' + token, 300, json.dumps({'user': user.id, 'version': user.session_version}))
    return {'token': token}


@app.post(PREFIX + '/auth/reset')
def reset(data: Reset, request: Request):
    limited(request, 'reset', maximum=10)
    value = cache.getdel('reset:' + data.token)
    if not value:
        raise MailError('重设凭证已过期或使用，请重新验证密保', 'expired', 400)
    info = json.loads(value)
    with Session() as db:
        user = db.scalar(select(User).where(User.id == info['user']).with_for_update())
        if not user or user.session_version != info['version']:
            raise MailError('重设凭证已失效', 'expired', 400)
        user.password_hash = passwords.hash(data.password)
        user.session_version += 1
        db.commit()
    return {'ok': True}


@app.get(PREFIX + '/me')
def me(request: Request, user=Depends(current_user)):
    return {'user': public_user(user), 'csrf': request.state.session['csrf']}


class Preferences(BaseModel):
    theme: Literal['light', 'dark']


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
    question: str | None = None
    answer: str | None = Field(default=None, min_length=2, max_length=200)


@app.post(PREFIX + '/me/password')
def password_change(data: PasswordChange, request: Request, user=Depends(current_user)):
    limited(request, 'password', user.id, 5)
    if not verify(data.currentPassword, user.password_hash):
        raise MailError('当前密码不正确', 'credentials', 400)
    with Session() as db:
        stored = db.get(User, user.id)
        stored.password_hash = passwords.hash(data.password)
        if data.question is not None:
            if data.question not in QUESTIONS or not data.answer or len(normalize_answer(data.answer)) < 2:
                raise MailError('密保信息不完整', 'validation', 422)
            stored.question, stored.answer_hash = data.question, passwords.hash(normalize_answer(data.answer))
        stored.session_version += 1
        db.commit()
    return {'ok': True}


def account_for(user, aid):
    with Session() as db:
        account = db.get(Account, aid)
    if not account or account.user_id != user.id:
        raise MailError('邮箱不存在', 'not_found', 404)
    return account


def account_view(account):
    return {'id': account.id, 'email': account.email, 'provider': account.provider, 'status': account.status}


def invalidate(user_id, aid):
    for key in cache.scan_iter(f'mail:{user_id}:{aid}:*', count=100):
        cache.delete(key)


@contextmanager
def provider_for(user, aid):
    account = account_for(user, aid)
    lock = cache.lock('account-lock:' + aid, timeout=600, blocking_timeout=2)
    if not lock.acquire():
        raise MailError('邮箱正在处理其他请求，请稍后重试', 'busy', 409)
    provider = None
    try:
        # Re-read after obtaining the lock to avoid using stale credentials.
        account = account_for(user, aid)
        def save_secret(value):
            with Session() as db:
                row = db.get(Account, aid)
                row.secret = encrypt(value)
                db.commit()
        cls = GmailApiProvider if account.provider == 'oauth' else GmailImapSmtpProvider
        provider = cls(account.email, decrypt(account.secret), save_secret)
        yield provider, account
    except (RefreshError, imaplib.IMAP4.error, smtplib.SMTPAuthenticationError):
        with Session() as db:
            row = db.get(Account, aid)
            if row:
                row.status = 'reconnect'
                db.commit()
        raise MailError('邮箱连接已失效，请重新授权或更新应用专用密码', 'reconnect', 401) from None
    except (TimeoutError, OSError):
        raise MailError('连接 Gmail 超时，请检查网络后重试', 'network', 502) from None
    finally:
        if provider:
            provider.close()
        try:
            lock.release()
        except LockError:
            pass


def save_account(user, email, kind, secret):
    canonical = canonical_email(email)
    with Session() as db:
        account = db.scalar(select(Account).where(Account.email == canonical))
        if account and account.user_id != user.id:
            raise MailError('此邮箱已连接其他平台账户', 'conflict', 409)
        if account:
            lock = cache.lock('account-lock:' + account.id, timeout=30, blocking_timeout=2)
            if not lock.acquire():
                raise MailError('邮箱正在使用中，请稍后再试', 'busy', 409)
            try:
                account.provider, account.secret, account.status, account.sync_state = kind, encrypt(secret), 'connected', '{}'
                db.commit()
                invalidate(user.id, account.id)
            finally:
                lock.release()
        else:
            account = Account(user_id=user.id, email=canonical, provider=kind, secret=encrypt(secret))
            db.add(account)
            try:
                db.commit()
            except IntegrityError:
                raise MailError('此邮箱已连接，请刷新', 'conflict', 409) from None
    return account_view(account)


@app.get(PREFIX + '/gmail-accounts')
def accounts(user=Depends(current_user)):
    with Session() as db:
        return [account_view(a) for a in db.scalars(select(Account).where(Account.user_id == user.id))]


class ImapConnect(BaseModel):
    email: str = Field(min_length=3, max_length=254, pattern=r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
    password: str = Field(min_length=16, max_length=64)
    port: Literal[465, 587] = 465


@app.post(PREFIX + '/gmail-accounts/imap')
def connect_imap(data: ImapConnect, request: Request, user=Depends(current_user)):
    limited(request, 'connect', user.id, 10)
    secret = {'password': data.password.replace(' ', ''), 'port': data.port}
    provider = None
    try:
        provider = GmailImapSmtpProvider(canonical_email(data.email), secret)
        provider.validate()
        # Gmail returns the authenticated primary account in its ID response where available.
        # Require the supplied full mailbox address; aliases must be connected via OAuth.
    except (imaplib.IMAP4.error, smtplib.SMTPAuthenticationError):
        raise MailError('认证失败，请使用 Gmail 主邮箱地址与应用专用密码，并确认账户允许 IMAP/SMTP', 'credentials', 400) from None
    except (OSError, smtplib.SMTPException):
        raise MailError('无法连接 Gmail，请检查网络或稍后再试', 'network', 502) from None
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
    cache.setex('oauth:' + state, 600, json.dumps({'user': user.id, 'session': request.cookies['fia_session']}))
    return {'url': url}


@app.get(PREFIX + '/gmail-accounts/oauth/callback')
def oauth_callback(request: Request, state: str = '', code: str = '', error: str = '', user=Depends(current_user)):
    value = cache.getdel('oauth:' + state)
    if not value:
        raise MailError('授权请求已过期，请重新连接', 'oauth_state', 400)
    value = json.loads(value)
    if value['user'] != user.id or not secrets.compare_digest(value['session'], request.cookies.get('fia_session', '')):
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
    lock = cache.lock('account-lock:' + aid, timeout=30, blocking_timeout=2)
    if not lock.acquire():
        raise MailError('邮箱正在使用中，请稍后重试', 'busy', 409)
    try:
        with Session() as db:
            db.execute(delete(Account).where(Account.id == aid, Account.user_id == user.id))
            db.commit()
        invalidate(user.id, aid)
    finally:
        lock.release()
    return {'ok': True}


def cached_call(user, aid, suffix, ttl, fn):
    account_for(user, aid)
    key = f'mail:{user.id}:{aid}:' + hashlib.sha256(suffix.encode()).hexdigest()
    cached = cache.get(key)
    if cached:
        return json.loads(cached)
    with provider_for(user, aid) as (provider, _):
        result = fn(provider)
        cache.setex(key, ttl, json.dumps(result))
    return result


@app.get(PREFIX + '/gmail-accounts/{aid}/threads')
def threads(aid: str, folder: str = 'INBOX', q: str = '', cursor: str = '', user=Depends(current_user)):
    if len(q) > 2000 or len(cursor) > 2000:
        raise MailError('搜索参数过长', 'validation', 422)
    return cached_call(user, aid, json.dumps(['list', folder, q, cursor]), 60, lambda p: p.list(folder, q, cursor))


@app.get(PREFIX + '/gmail-accounts/{aid}/threads/{tid}')
def thread(aid: str, tid: str, user=Depends(current_user)):
    result = cached_call(user, aid, 'thread:' + tid, 300, lambda p: p.thread(tid))
    return [{**m, 'html': safe_html(m['html']) if m['html'] else ''} for m in result]


@app.get(PREFIX + '/gmail-accounts/{aid}/messages/{mid}')
def message(aid: str, mid: str, remote: bool = False, user=Depends(current_user)):
    result = cached_call(user, aid, 'message:' + mid, 300, lambda p: p.message(mid))
    return {**result, 'html': safe_html(result['html'], remote) if result['html'] else ''}


@app.get(PREFIX + '/gmail-accounts/{aid}/messages/{mid}/attachments/{part}')
def attachment(aid: str, mid: str, part: str, user=Depends(current_user)):
    with provider_for(user, aid) as (provider, _):
        content, name, content_type = provider.attachment(mid, part)
    return Response(content, media_type='application/octet-stream', headers={'Content-Disposition': "attachment; filename*=UTF-8''" + urlquote(name, safe='')})


@app.get(PREFIX + '/gmail-accounts/{aid}/labels')
def labels(aid: str, user=Depends(current_user)):
    return cached_call(user, aid, 'labels', 60, lambda p: p.labels())


class LabelAction(BaseModel):
    action: Literal['create', 'rename', 'delete']
    id: str = ''
    name: str = Field(default='', max_length=200)


@app.post(PREFIX + '/gmail-accounts/{aid}/labels')
def label_action(aid: str, data: LabelAction, user=Depends(current_user)):
    if data.action != 'delete' and not data.name.strip():
        raise MailError('标签名称不能为空', 'validation', 422)
    with provider_for(user, aid) as (provider, _):
        if data.action != 'create' and not any(l['id'] == data.id and l.get('type') == 'user' for l in provider.labels()):
            raise MailError('仅能修改自定义标签', 'validation', 422)
        provider.label(data.action, data.id, data.name.strip())
        invalidate(user.id, aid)
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
    with provider_for(user, aid) as (provider, _):
        if data.threadIds:
            provider.thread_modify(data.threadIds, data.add, data.remove, data.action)
        elif data.action == 'labels':
            provider.modify(data.ids, data.add, data.remove)
        else:
            for identifier in data.ids:
                provider.move(identifier, data.action == 'trash')
        invalidate(user.id, aid)
    return {'ok': True}


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
    with provider_for(user, aid) as (provider, account):
        key = f'draft-version:{user.id}:{aid}:{data.composeId}'
        previous = json.loads(cache.get(key) or '{}')
        if previous.get('version', 0) >= data.version:
            return previous
        msg = materialize(data, user, aid, provider, account.email)
        did = provider.draft_save(msg, previous.get('id') or data.draftId, data.threadId)
        saved = provider.draft_get(did)
        result = {'id': did, 'version': data.version, 'attachments': [{**a, 'messageId': saved['id']} for a in saved['attachments']]}
        cache.setex(key, 86400, json.dumps(result))
        invalidate(user.id, aid)
        return result


@app.delete(PREFIX + '/gmail-accounts/{aid}/drafts/{did}')
def draft_delete(aid: str, did: str, user=Depends(current_user)):
    with provider_for(user, aid) as (provider, _):
        provider.draft_delete(did)
        invalidate(user.id, aid)
    return {'ok': True}


@app.post(PREFIX + '/gmail-accounts/{aid}/send')
def send(aid: str, data: Compose, request: Request, user=Depends(current_user)):
    limited(request, 'send', user.id, 30, 3600)
    envelope = recipients(data.model_dump())
    with provider_for(user, aid) as (provider, account):
        key = f'send:{user.id}:{aid}:{data.composeId}'
        old = cache.get(key)
        if old:
            result = json.loads(old)
            if result['status'] == 'sent':
                return result
            raise MailError('此邮件的发送结果尚不明确，请检查已发送，勿重复发送', 'send_uncertain', 409)
        msg = materialize(data, user, aid, provider, account.email)
        cache.setex(key, 86400 * 7, json.dumps({'status': 'pending'}))
        try:
            result = provider.send(msg, envelope, data.threadId)
        except Exception:
            raise MailError('发送结果尚不明确，请检查已发送后再决定是否重新撰写', 'send_uncertain', 409) from None
        response = {'status': 'sent', 'result': result}
        cache.setex(key, 86400 * 7, json.dumps(response))
        did = data.draftId
        draft_state = cache.get(f'draft-version:{user.id}:{aid}:{data.composeId}')
        if draft_state:
            did = json.loads(draft_state)['id']
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
        cache.setex(key, 86400 * 7, json.dumps(response))
        return response


@app.post(PREFIX + '/gmail-accounts/{aid}/sync')
def sync(aid: str, folder: str = 'INBOX', user=Depends(current_user)):
    cleanup_uploads()
    with provider_for(user, aid) as (provider, account):
        state, changed, reset = provider.sync(json.loads(account.sync_state), folder)
        with Session() as db:
            stored = db.get(Account, aid)
            stored.sync_state, stored.status = json.dumps(state), 'connected'
            db.commit()
        if changed:
            invalidate(user.id, aid)
    return {'changed': changed, 'reset': reset}
