import imaplib
import json
import re
import smtplib
import ssl
import time
from contextlib import contextmanager
from email import policy
from email.parser import BytesParser
from email.utils import formatdate
from typing import Protocol

import httplib2
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_httplib2 import AuthorizedHttp
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from .core import GOOGLE_ID, GOOGLE_SECRET, SCOPES
from .mail import MailError, b64, unb64, parse_message, extract_attachment


class MailProvider(Protocol):
    """Shared mail interface; provider message IDs are opaque to the application."""
    def close(self): ...
    def labels(self): ...
    def label(self, action, identifier, name): ...
    def list(self, folder='INBOX', query='', cursor=''): ...
    def message(self, identifier): ...
    def thread(self, identifier): ...
    def attachment(self, identifier, part_id): ...
    def modify(self, identifiers, add, remove): ...
    def thread_modify(self, identifiers, add, remove, action): ...
    def move(self, identifier, trash=True): ...
    def send(self, msg, envelope, thread_id=None): ...
    def drafts(self): ...
    def draft_get(self, identifier): ...
    def draft_save(self, msg, identifier=None, thread_id=None): ...
    def draft_delete(self, identifier): ...
    def sync(self, state, folder): ...


class GmailApiProvider:
    def __init__(self, email, secret, save_secret=None):
        self.email = email
        credentials = Credentials.from_authorized_user_info(secret, SCOPES)
        if not credentials.valid:
            credentials.refresh(Request())
            if save_secret:
                save_secret(json.loads(credentials.to_json()))
        self.service = build('gmail', 'v1', http=AuthorizedHttp(credentials, http=httplib2.Http(timeout=30)), cache_discovery=False)
        self.users = self.service.users()

    def close(self):
        self.service.close()

    def run(self, req, retry=True):
        try:
            return req.execute(num_retries=2 if retry else 0)
        except HttpError as exc:
            if exc.resp.status == 404:
                raise MailError('邮件或标签已不存在，请刷新', 'not_found', 404) from None
            if exc.resp.status in (401, 403):
                raise MailError('Google 拒绝访问，请检查授权或账户限制', 'authorization', 403) from None
            if exc.resp.status == 429:
                raise MailError('Google 请求额度暂时受限，请稍后重试', 'rate_limit', 429) from None
            raise MailError('Gmail 服务暂时不可用，请稍后重试') from None

    def profile(self):
        return self.run(self.users.getProfile(userId='me'))

    def labels(self):
        return self.run(self.users.labels().list(userId='me')).get('labels', [])

    def label(self, action, identifier, name):
        resource = self.users.labels()
        if action == 'create':
            return self.run(resource.create(userId='me', body={'name': name, 'labelListVisibility': 'labelShow', 'messageListVisibility': 'show'}))
        if action == 'rename':
            return self.run(resource.patch(userId='me', id=identifier, body={'name': name}))
        return self.run(resource.delete(userId='me', id=identifier))

    def raw(self, identifier):
        result = self.run(self.users.messages().get(userId='me', id=identifier, format='raw'))
        return unb64(result['raw']), result

    def message(self, identifier):
        from .mime_parts import gmail_message
        return gmail_message(self, self.run(self.users.messages().get(userId='me', id=identifier, format='full')))

    def attachment(self, identifier, part_id):
        # Fetch the MIME part through the dedicated attachment API when available.
        full = self.run(self.users.messages().get(userId='me', id=identifier, format='full'))
        flat = []
        def walk(part):
            flat.append(part)
            for child in part.get('parts', []):
                walk(child)
        walk(full['payload'])
        try:
            part = next(p for p in flat if p.get('partId', '') == part_id[6:]) if part_id.startswith('gmail:') else flat[int(part_id)]
        except (ValueError, IndexError, StopIteration):
            raise MailError('附件不存在', 'not_found', 404)
        body = part.get('body', {})
        if body.get('attachmentId'):
            payload = self.run(self.users.messages().attachments().get(userId='me', messageId=identifier, id=body['attachmentId']))
            return unb64(payload['data']), part.get('filename') or 'attachment', part.get('mimeType', 'application/octet-stream')
        if part_id.startswith('gmail:'):
            return unb64(body.get('data', '')), part.get('filename') or 'attachment', part.get('mimeType', 'application/octet-stream')
        return extract_attachment(self.raw(identifier)[0], part_id)

    def thread(self, identifier):
        from .mime_parts import gmail_message
        result = self.run(self.users.threads().get(userId='me', id=identifier, format='full'))
        return [gmail_message(self, m) for m in result.get('messages', [])]

    def list(self, folder='INBOX', query='', cursor=''):
        args = {'userId': 'me', 'maxResults': 30, 'includeSpamTrash': folder in ('SPAM', 'TRASH')}
        if folder != 'ALL':
            args['labelIds'] = [folder]
        if query:
            args['q'] = query
        if cursor:
            args['pageToken'] = cursor
        result = self.run(self.users.threads().list(**args))
        items = []
        details, errors = {}, []
        def receive(identifier, response, exception):
            if exception:
                if not isinstance(exception, HttpError) or exception.resp.status != 404:
                    errors.append(exception)
            else:
                details[identifier] = response
        threads = result.get('threads', [])
        for start in range(0, len(threads), 20):
            batch = self.service.new_batch_http_request(callback=receive)
            for thread in threads[start:start + 20]:
                batch.add(self.users.threads().get(userId='me', id=thread['id'], format='metadata', metadataHeaders=['Subject', 'From', 'Date', 'To']), request_id=thread['id'])
            batch.execute()
        if errors:
            raise errors[0]
        for thread in threads:
            detail = details.get(thread['id'], {})
            messages = detail.get('messages', [])
            if not messages:
                continue
            latest = messages[-1]
            headers = {h['name'].lower(): h['value'] for h in latest.get('payload', {}).get('headers', [])}
            items.append({'id': latest['id'], 'threadId': thread['id'], 'subject': headers.get('subject', '(无主题)'), 'from': headers.get('from', ''), 'date': headers.get('date', ''), 'snippet': latest.get('snippet', ''), 'labels': list(set(l for m in messages for l in m.get('labelIds', []))), 'count': len(messages)})
        return {'items': items, 'nextCursor': result.get('nextPageToken', '')}

    def modify(self, identifiers, add, remove):
        return self.run(self.users.messages().batchModify(userId='me', body={'ids': identifiers, 'addLabelIds': add, 'removeLabelIds': remove}))

    def thread_modify(self, identifiers, add, remove, action):
        for identifier in identifiers:
            resource = self.users.threads()
            if action == 'labels':
                self.run(resource.modify(userId='me', id=identifier, body={'addLabelIds': add, 'removeLabelIds': remove}))
            else:
                method = resource.trash if action == 'trash' else resource.untrash
                self.run(method(userId='me', id=identifier))

    def move(self, identifier, trash=True):
        method = self.users.messages().trash if trash else self.users.messages().untrash
        return self.run(method(userId='me', id=identifier))

    def send(self, msg, envelope, thread_id=None):
        body = {'raw': b64(msg.as_bytes())}
        if thread_id:
            body['threadId'] = thread_id
        return self.run(self.users.messages().send(userId='me', body=body), retry=False)

    def drafts(self):
        result = self.run(self.users.drafts().list(userId='me', maxResults=100))
        return [{'id': d['id'], 'messageId': d['message']['id']} for d in result.get('drafts', [])]

    def draft_get(self, identifier):
        draft = self.run(self.users.drafts().get(userId='me', id=identifier, format='raw'))
        m = draft['message']
        return parse_message(unb64(m['raw']), m['id'], m['threadId'], ['DRAFT'])

    def draft_save(self, msg, identifier=None, thread_id=None):
        body = {'message': {'raw': b64(msg.as_bytes())}}
        if thread_id:
            body['message']['threadId'] = thread_id
        method = self.users.drafts().update(userId='me', id=identifier, body=body) if identifier else self.users.drafts().create(userId='me', body=body)
        result = self.run(method, retry=False)
        return result['id']

    def draft_delete(self, identifier):
        return self.run(self.users.drafts().delete(userId='me', id=identifier), retry=False)

    def draft_send(self, identifier):
        return self.run(self.users.drafts().send(userId='me', body={'id': identifier}), retry=False)

    def sync(self, state, folder):
        previous = state.get('historyId')
        current = self.profile()['historyId']
        changed, reset = not previous, False
        if previous:
            token = None
            try:
                while True:
                    result = self.users.history().list(userId='me', startHistoryId=previous, pageToken=token).execute(num_retries=2)
                    changed = changed or bool(result.get('history'))
                    token = result.get('nextPageToken')
                    if not token:
                        break
            except HttpError as exc:
                if exc.resp.status != 404:
                    raise
                changed, reset = True, True
        return {'historyId': current}, changed, reset


def utf7_encode(value):
    result, buffer = [], []
    def flush():
        if buffer:
            import base64
            result.append('&' + base64.b64encode(''.join(buffer).encode('utf-16-be')).decode().rstrip('=').replace('/', ',') + '-')
            buffer.clear()
    for char in value:
        if ' ' <= char <= '~':
            flush()
            result.append('&-' if char == '&' else char)
        else:
            buffer.append(char)
    flush()
    return ''.join(result)


def utf7_decode(value):
    import base64
    return re.sub(r'&([^-]*)-', lambda m: '&' if not m[1] else base64.b64decode(m[1].replace(',', '/') + '=' * (-len(m[1]) % 4)).decode('utf-16-be'), value)


def quote(value):
    if '\r' in value or '\n' in value or '\x00' in value:
        raise MailError('无效的邮箱参数', 'validation', 422)
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


# -- Generic IMAP/SMTP provider ------------------------------------------------

DEFAULT_IMAP = {'host': 'imap.gmail.com', 'port': 993, 'security': 'ssl'}
DEFAULT_SMTP = {'host': 'smtp.gmail.com', 'port': 465, 'security': 'ssl'}
SPECIAL_USE = {'\\sent': 'SENT', '\\drafts': 'DRAFT', '\\trash': 'TRASH', '\\junk': 'SPAM', '\\all': 'ALL', '\\archive': 'ARCHIVE', '\\flagged': 'STARRED', '\\important': 'IMPORTANT'}
FALLBACK_NAMES = {
    'SENT': ('sent', 'sent items', 'sent messages', 'sent mail', '已发送', '已发送邮件', '发件箱'),
    'DRAFT': ('drafts', 'draft', '草稿', '草稿箱'),
    'TRASH': ('trash', 'deleted', 'deleted items', 'deleted messages', '已删除', '已删除邮件', '回收站', '垃圾箱'),
    'SPAM': ('junk', 'spam', 'junk e-mail', 'junk email', '垃圾邮件'),
    'ARCHIVE': ('archive', 'archives', '归档', '存档'),
    'ALL': ('all mail', 'all', '所有邮件'),
}
SUBJECT_PREFIX = re.compile(r'^\s*(?:(?:re|fw|fwd|aw|sv|回复|回覆|答复|转发|转)\s*(?:\[\d+\])?\s*[:：]\s*)+', re.IGNORECASE)
HEADER_FIELDS = b'(UID FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE TO CC REFERENCES IN-REPLY-TO MESSAGE-ID)])'
FULL_FIELDS = b'(UID FLAGS BODY.PEEK[])'
SCAN_LIMIT = 200
PAGE_SIZE = 30
THREAD_LIMIT = 100


def normalize_subject(value):
    return SUBJECT_PREFIX.sub('', str(value or '')).strip().lower()


class ImapSmtpProvider:
    """Standard IMAP + SMTP provider for any mailbox exposing IMAP and SMTP.

    Folders act as labels, threads are derived from References/In-Reply-To/Subject,
    and every message is addressed by an opaque folder-scoped identifier.
    """

    def __init__(self, email, secret, save_secret=None):
        self.email, self.secret = email, secret
        self.imap = None
        config = secret.get('imap') or DEFAULT_IMAP
        try:
            host, port = str(config['host']), int(config['port'])
        except (KeyError, TypeError, ValueError):
            raise MailError('IMAP 服务器配置不完整，请重新连接', 'configuration', 422) from None
        try:
            if config.get('security', 'ssl') == 'ssl':
                self.imap = imaplib.IMAP4_SSL(host, port, ssl_context=ssl.create_default_context(), timeout=30)
            else:
                self.imap = imaplib.IMAP4(host, port, timeout=30)
                self.imap.starttls(ssl_context=ssl.create_default_context())
        except (OSError, imaplib.IMAP4.error):
            raise MailError('无法连接 IMAP 服务器，请检查主机、端口与加密方式', 'network', 502) from None
        try:
            self.imap.login(email, secret['password'])
            self.caps = set(b' '.join(self._ok(self.imap.capability())).decode().upper().split())
            self.folders = self._folders()
        except Exception:
            self.close()
            raise
        self.system, self.fallback = self._system_folders()

    def close(self):
        try:
            if self.imap:
                self.imap.logout()
        except Exception:
            pass

    def _ok(self, result):
        status, data = result
        if status != 'OK':
            raise MailError('邮件服务器无法完成该操作，请刷新后重试')
        return data

    # -- folders ---------------------------------------------------------------

    def _folders(self):
        output = []
        for item in self._ok(self.imap.list()):
            if not item:
                continue
            match = re.match(rb'\((.*?)\)\s+(?:NIL|"[^"]*")\s+(.*)', item)
            if not match:
                continue
            flags = match[1].decode().split()
            name = match[2].decode().strip()
            if name.startswith('"') and name.endswith('"'):
                name = name[1:-1].replace('\\"', '"').replace('\\\\', '\\')
            if '\\Noselect' not in flags:
                output.append({'wire': name, 'name': utf7_decode(name), 'flags': flags})
        return output

    def _system_folders(self):
        system, fallback = {'INBOX': 'INBOX'}, {}
        for folder in self.folders:
            for flag in folder['flags']:
                key = SPECIAL_USE.get(flag.lower())
                if key and key not in system:
                    system[key] = folder['wire']
        for key, names in FALLBACK_NAMES.items():
            if key in system:
                continue
            for folder in self.folders:
                if folder['name'].strip().lower() in names or folder['wire'].strip().lower() in names:
                    fallback[key] = folder['wire']
                    break
        return system, fallback

    def _system(self, key):
        return self.system.get(key) or self.fallback.get(key)

    def folder_wire(self, identifier):
        identifier = identifier or 'INBOX'
        if identifier == 'INBOX':
            return 'INBOX'
        if identifier == 'ALL':
            return self.system.get('ALL') or self._system('ARCHIVE') or 'INBOX'
        if identifier == 'STARRED':
            return self.system.get('ALL') or self._system('ARCHIVE') or 'INBOX'
        if identifier.startswith('label:'):
            name = unb64(identifier[6:]).decode('utf-8', 'replace')
            if any(folder['wire'] == name for folder in self.folders):
                return name
            raise MailError('标签不存在，请刷新', 'not_found', 404)
        wire = self._system(identifier)
        if not wire:
            raise MailError('该邮箱没有对应的系统文件夹', 'not_found', 404)
        return wire

    def _folder_label(self, wire):
        for key, value in {**self.fallback, **self.system}.items():
            if key not in ('ALL', 'ARCHIVE', 'IMPORTANT', 'STARRED') and value == wire:
                return key
        return None

    def _select(self, wire, expected=None):
        mailbox = quote(wire)
        if 'CONDSTORE' in self.caps and getattr(self, 'readonly', False):
            mailbox += ' (CONDSTORE)'
        status, _ = self.imap.select(mailbox, readonly=getattr(self, 'readonly', False))
        if status != 'OK':
            raise MailError('无法打开邮件文件夹，请刷新后重试', 'not_found', 404)
        response = self.imap.response('UIDVALIDITY')[1]
        validity = (response[0] or b'0').decode() if response else '0'
        modseq = self.imap.response('HIGHESTMODSEQ')[1]
        self.modseq = (modseq[0] or b'0').decode() if modseq else '0'
        if expected and str(expected) != validity:
            raise MailError('邮件已不存在，请刷新', 'not_found', 404)
        return validity

    # -- primitives ------------------------------------------------------------

    def _search(self, *criteria):
        criteria = [item if isinstance(item, bytes) else str(item).encode() for item in criteria]
        for prefix in ((b'CHARSET', b'UTF-8'), ()):
            try:
                status, data = self.imap.uid('SEARCH', *prefix, *criteria)
            except (imaplib.IMAP4.error, UnicodeEncodeError):
                continue
            if status == 'OK':
                return (data[0] or b'').split()
        raise MailError('搜索失败，请稍后重试', 'search_failed', 502)

    def _fetch(self, uids, query):
        if not uids:
            return []
        rows = []
        for row in self._ok(self.imap.uid('FETCH', b','.join(uids), query)):
            if not isinstance(row, tuple):
                continue
            header, raw = row
            uid = re.search(rb'UID (\d+)', header)
            if not uid:
                continue
            flags = re.search(rb'FLAGS \((.*?)\)', header)
            rows.append((uid[1].decode(), flags[1] if flags else b'', raw))
        return rows

    def _append(self, mailbox, msg, flags=''):
        return self._ok(self.imap.append(mailbox, '(' + flags + ')' if flags else None, imaplib.Time2Internaldate(time.time()), msg.as_bytes()))

    def _copy(self, uid, target):
        return self._ok(self.imap.uid('COPY', uid, quote(target)))

    def _delete(self, uid):
        self._ok(self.imap.uid('STORE', uid, '+FLAGS.SILENT', '(\\Deleted)'))
        if 'UIDPLUS' in self.caps:
            self._ok(self.imap.uid('EXPUNGE', uid))
        else:
            self._ok(self.imap.expunge())

    def _move(self, uid, target):
        if 'MOVE' in self.caps:
            self._ok(self.imap.uid('MOVE', uid, quote(target)))
        else:
            self._copy(uid, target)
            self._delete(uid)

    def _archive(self, uid):
        # Gmail-style \All already holds every message: remove the folder membership only.
        if self.system.get('ALL'):
            self._delete(uid)
            return
        archive = self._system('ARCHIVE')
        if archive:
            self._copy(uid, archive)
            self._delete(uid)
            return
        raise MailError('该邮箱没有归档文件夹，暂不支持归档', 'unsupported', 422)

    @staticmethod
    def _encode(*parts):
        return b64(json.dumps(list(parts)).encode('utf-8'))

    @staticmethod
    def _decode(identifier):
        try:
            parts = json.loads(unb64(identifier).decode('utf-8'))
        except Exception:
            raise MailError('无效邮件标识', 'validation', 422) from None
        if not isinstance(parts, list) or len(parts) < 3 or parts[0] not in ('m', 't'):
            raise MailError('无效邮件标识', 'validation', 422)
        return parts

    @staticmethod
    def _parse(raw):
        return BytesParser(policy=policy.default).parsebytes(raw)

    @staticmethod
    def _thread_key(msg):
        references = str(msg.get('References') or '').split()
        in_reply = str(msg.get('In-Reply-To') or '').split()
        message_id = str(msg.get('Message-ID') or '').strip()
        root = (references[0] if references else in_reply[0] if in_reply else message_id).strip()
        if root and root.startswith('<'):
            return 'mid', root
        subject = normalize_subject(msg.get('Subject'))
        return ('subj', subject) if subject else ('uid', message_id)

    def _labels(self, flagdata, wire=''):
        labels = []
        if b'\\Seen' not in flagdata:
            labels.append('UNREAD')
        if b'\\Flagged' in flagdata:
            labels.append('STARRED')
        if b'\\Draft' in flagdata:
            labels.append('DRAFT')
        folder = self._folder_label(wire)
        if folder and folder not in labels:
            labels.append(folder)
        return labels

    @staticmethod
    def _criteria(query):
        criteria, free = [], []
        for token in str(query).split():
            lower = token.lower()
            for prefix, field in (('from:', 'FROM'), ('to:', 'TO'), ('subject:', 'SUBJECT')):
                if lower.startswith(prefix):
                    value = token[len(prefix):].strip('"')
                    if value:
                        criteria += [b'HEADER', field.encode(), value.encode('utf-8')]
                    break
            else:
                free.append(token)
        if free:
            criteria += [b'TEXT', ' '.join(free).encode('utf-8')]
        return criteria or [b'ALL']

    # -- labels ----------------------------------------------------------------

    def labels(self):
        output = [
            {'id': 'INBOX', 'name': '收件箱', 'type': 'system'},
            {'id': 'STARRED', 'name': '已加星标', 'type': 'system'},
            {'id': 'SENT', 'name': '已发送', 'type': 'system'},
            {'id': 'DRAFT', 'name': '草稿', 'type': 'system'},
            {'id': 'ALL', 'name': '所有邮件', 'type': 'system'},
            {'id': 'SPAM', 'name': '垃圾邮件', 'type': 'system'},
            {'id': 'TRASH', 'name': '回收站', 'type': 'system'},
        ]
        used = set(self.system.values()) | set(self.fallback.values()) | {'INBOX'}
        for folder in self.folders:
            if folder['wire'] in used:
                continue
            output.append({'id': 'label:' + b64(folder['wire'].encode('utf-8')), 'name': folder['name'], 'type': 'user'})
        return output

    def label(self, action, identifier, name):
        if action == 'create':
            self._ok(self.imap.create(quote(utf7_encode(name))))
        else:
            if not identifier.startswith('label:'):
                raise MailError('不能修改系统标签', 'validation', 422)
            wire = self.folder_wire(identifier)
            if action == 'rename':
                self._ok(self.imap.rename(quote(wire), quote(utf7_encode(name))))
            else:
                self._ok(self.imap.delete(quote(wire)))
        self.folders = self._folders()
        self.system, self.fallback = self._system_folders()
        return {}

    # -- reading ---------------------------------------------------------------

    def _open(self, identifier):
        parts = self._decode(identifier)
        if parts[0] != 'm':
            raise MailError('无效邮件标识', 'validation', 422)
        wire, validity, uid = parts[1], str(parts[2]), str(parts[3])
        self._select(wire, validity)
        rows = self._fetch([uid.encode()], FULL_FIELDS)
        if not rows:
            raise MailError('邮件已不存在，请刷新', 'not_found', 404)
        return wire, validity, rows[0][0], rows[0][1], rows[0][2]

    def message(self, identifier):
        from .mime_parts import imap_message
        return imap_message(self, identifier)

    def attachment(self, identifier, part_id):
        if part_id.startswith('mime:'):
            from .mime_parts import imap_parts, imap_part_bytes
            _, _, uid, parts = imap_parts(self, identifier)
            part = next((part for part in parts if part['id'] == part_id and part['attachment']), None)
            if not part:
                raise MailError('附件不存在', 'not_found', 404)
            return imap_part_bytes(self, uid, part), part['name'] or 'attachment', part['type']
        return extract_attachment(self._open(identifier)[4], part_id)

    def thread(self, identifier):
        parts = self._decode(identifier)
        if parts[0] != 't' or len(parts) < 6:
            raise MailError('无效会话标识', 'validation', 422)
        wire, validity, anchor, kind, key = parts[1], str(parts[2]), str(parts[3]), parts[4], parts[5]
        self._select(wire, validity)
        rows = self._fetch([anchor.encode()], HEADER_FIELDS)
        if not rows:
            raise MailError('邮件已不存在，请刷新', 'not_found', 404)
        anchor_msg = self._parse(rows[0][2])
        uids = {anchor}
        if kind == 'mid':
            roots = [key, str(anchor_msg.get('Message-ID') or '')] + str(anchor_msg.get('References') or '').split()[:10]
            for root in dict.fromkeys(value for value in roots if value):
                for field in (b'Message-ID', b'References', b'In-Reply-To'):
                    uids.update(item.decode() for item in self._search(b'HEADER', field, root.encode('utf-8')))
        else:
            base = normalize_subject(anchor_msg.get('Subject'))
            if base:
                for uid, _, raw in self._fetch(self._search('ALL')[-SCAN_LIMIT:], HEADER_FIELDS):
                    if normalize_subject(self._parse(raw).get('Subject')) == base:
                        uids.add(uid)
        ordered = sorted(uids, key=lambda value: int(value) if value.isdecimal() else 0)[:THREAD_LIMIT]
        messages = []
        for uid in ordered:
            message = self.message(self._encode('m', wire, validity, uid))
            message['threadId'] = identifier
            messages.append((int(uid), message))
        return [message for _, message in sorted(messages, key=lambda pair: pair[0])]

    def list(self, folder='INBOX', query='', cursor=''):
        if folder == 'STARRED':
            wire = self.folder_wire('STARRED')
            validity = self._select(wire)
            ids = self._search('FLAGGED')
        else:
            wire = self.folder_wire(folder)
            validity = self._select(wire)
            ids = self._search(*self._criteria(query)) if query else self._search('ALL')
        offset = 0
        if cursor:
            try:
                offset = int(cursor)
                if offset < 0:
                    raise ValueError()
            except ValueError:
                raise MailError('无效分页参数', 'validation', 422) from None
        remaining = [uid for uid in ids if offset == 0 or int(uid) < offset]
        window = list(reversed(remaining))[:SCAN_LIMIT]
        rows = {}
        for start in range(0, len(window), 100):
            for uid, flagdata, raw in self._fetch(window[start:start + 100], HEADER_FIELDS):
                rows[uid] = (flagdata, raw)
        groups, order, last_uid, truncated = {}, [], None, False
        for raw_uid in window:
            uid = raw_uid.decode()
            if uid not in rows:
                continue
            flagdata, raw = rows[uid]
            msg = self._parse(raw)
            kind, key = self._thread_key(msg)
            group_key = kind + ':' + key
            if group_key in groups:
                group = groups[group_key]
            elif len(order) >= PAGE_SIZE:
                truncated = True
                break
            else:
                group = {'anchor': uid, 'count': 0, 'labels': [], 'subject': str(msg.get('Subject') or '(无主题)'), 'from': str(msg.get('From') or ''), 'date': str(msg.get('Date') or ''), 'messageId': str(msg.get('Message-ID') or '')}
                groups[group_key] = group
                order.append(group_key)
            group['count'] += 1
            group['labels'] = list(dict.fromkeys(group['labels'] + self._labels(flagdata, wire)))
            last_uid = uid
        items = []
        for group_key in order:
            group = groups[group_key]
            kind, key = group_key.split(':', 1)
            items.append({
                'id': self._encode('m', wire, validity, group['anchor']),
                'threadId': self._encode('t', wire, validity, group['anchor'], kind, key),
                'subject': group['subject'], 'from': group['from'], 'to': '', 'cc': '', 'bcc': '',
                'date': group['date'], 'messageId': group['messageId'], 'references': '',
                'labels': group['labels'], 'snippet': '', 'text': '', 'html': '', 'attachments': [],
                'count': group['count'],
            })
        more = truncated or len(remaining) > len(window)
        return {'items': items, 'nextCursor': last_uid if more and last_uid else ''}

    # -- writing ---------------------------------------------------------------

    def modify(self, identifiers, add, remove):
        for identifier in identifiers:
            parts = self._decode(identifier)
            if parts[0] != 'm':
                raise MailError('无效邮件标识', 'validation', 422)
            wire, validity, uid = parts[1], str(parts[2]), str(parts[3])
            self._select(wire, validity)
            flags_add, flags_remove = [], []
            for label in add:
                if label == 'UNREAD':
                    flags_remove.append('\\Seen')
                elif label == 'STARRED':
                    flags_add.append('\\Flagged')
            for label in remove:
                if label == 'UNREAD':
                    flags_add.append('\\Seen')
                elif label == 'STARRED':
                    flags_remove.append('\\Flagged')
            if flags_add:
                self._ok(self.imap.uid('STORE', uid, '+FLAGS.SILENT', '(' + ' '.join(flags_add) + ')'))
            if flags_remove:
                self._ok(self.imap.uid('STORE', uid, '-FLAGS.SILENT', '(' + ' '.join(flags_remove) + ')'))
            added = []
            for label in add:
                if label in ('UNREAD', 'STARRED'):
                    continue
                target = self.folder_wire(label)
                if target != wire:
                    self._copy(uid, target)
                    added.append(target)
            for label in remove:
                if label in ('UNREAD', 'STARRED'):
                    continue
                if self.folder_wire(label) != wire:
                    continue
                if label == 'INBOX' and not added:
                    self._archive(uid)
                else:
                    self._delete(uid)

    def thread_modify(self, identifiers, add, remove, action):
        identifiers = [message['id'] for identifier in identifiers for message in self.thread(identifier)]
        if action == 'labels':
            self.modify(identifiers, add, remove)
            return
        for identifier in identifiers:
            self.move(identifier, action == 'trash')

    def move(self, identifier, trash=True):
        parts = self._decode(identifier)
        if parts[0] != 'm':
            raise MailError('无效邮件标识', 'validation', 422)
        wire, validity, uid = parts[1], str(parts[2]), str(parts[3])
        target = self.folder_wire('TRASH' if trash else 'INBOX')
        if target == wire:
            raise MailError('邮件已在该文件夹中', 'validation', 422)
        self._select(wire, validity)
        self._move(uid, target)

    # -- outgoing --------------------------------------------------------------

    @contextmanager
    def smtp(self):
        config = self.secret.get('smtp') or DEFAULT_SMTP
        try:
            host, port = str(config['host']), int(config['port'])
        except (KeyError, TypeError, ValueError):
            raise MailError('SMTP 服务器配置不完整，请重新连接', 'configuration', 422) from None
        security = config.get('security', 'ssl')
        if security == 'ssl':
            connection = smtplib.SMTP_SSL(host, port, context=ssl.create_default_context(), timeout=30)
        else:
            connection = smtplib.SMTP(host, port, timeout=30)
            connection.ehlo()
            connection.starttls(context=ssl.create_default_context())
            connection.ehlo()
        try:
            connection.login(self.email, self.secret['password'])
            yield connection
        finally:
            try:
                connection.quit()
            except Exception:
                connection.close()

    def validate(self):
        with self.smtp():
            pass

    def send(self, msg, envelope, thread_id=None):
        msg['Date'] = formatdate(localtime=False)
        if 'Bcc' in msg:
            del msg['Bcc']
        with self.smtp() as smtp:
            refused = smtp.sendmail(self.email, envelope, msg.as_bytes())
        result = {'id': str(msg['Message-ID']), 'refused': list(refused)}
        try:
            self._append(quote(self.folder_wire('SENT')), msg, '\\Seen')
        except Exception:
            result['warning'] = '邮件已发送，但未能保存到已发送文件夹，请在服务端确认'
        return result

    # -- drafts ----------------------------------------------------------------

    def drafts(self):
        wire = self.folder_wire('DRAFT')
        validity = self._select(wire)
        output = []
        for uid, _, raw in self._fetch(self._search('ALL')[-100:], HEADER_FIELDS):
            output.append({'id': self._encode('m', wire, validity, uid), 'messageId': str(self._parse(raw).get('Message-ID') or '')})
        return list(reversed(output))

    def draft_get(self, identifier):
        result = self.message(identifier)
        if 'DRAFT' not in result['labels']:
            raise MailError('此邮件不是草稿', 'validation', 422)
        return result

    def draft_save(self, msg, identifier=None, thread_id=None):
        wire = self.folder_wire('DRAFT')
        if identifier:
            self.draft_get(identifier)
        validity = self._select(wire)
        appended = self._append(quote(wire), msg, '\\Draft')
        uid = None
        match = re.search(rb'APPENDUID (\d+) (\d+)', b' '.join(item for item in (appended or []) if isinstance(item, bytes)))
        if match and match.group(1).decode() == validity:
            uid = match.group(2).decode()
        if not uid:
            message_id = str(msg.get('Message-ID') or '')
            ids = self._search(b'HEADER', b'Message-ID', message_id.encode('utf-8')) if message_id and 'UIDPLUS' in self.caps else []
            if not ids:
                raise MailError('草稿可能已保存，请刷新草稿箱后确认', 'uncertain', 409)
            uid = ids[-1].decode()
        if identifier and identifier != self._encode('m', wire, validity, uid):
            try:
                self.draft_delete(identifier)
            except MailError:
                pass
        return self._encode('m', wire, validity, uid)

    def draft_delete(self, identifier):
        self.draft_get(identifier)
        parts = self._decode(identifier)
        self._select(parts[1], str(parts[2]))
        self._delete(str(parts[3]))

    # -- sync ------------------------------------------------------------------

    def sync(self, state, folder):
        wire = self.folder_wire(folder)
        validity = self._select(wire)
        response = self.imap.response('UIDNEXT')[1]
        uidnext = (response[0] or b'0').decode() if response else '0'
        previous = state.get(folder)
        reset = bool(previous) and previous[0] != validity
        state[folder] = [validity, uidnext]
        # Flags can change without UIDNEXT moving: invalidate the active view each poll.
        return state, True, reset
