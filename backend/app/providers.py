import imaplib
import json
import re
import smtplib
import ssl
from contextlib import contextmanager
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
        raw, info = self.raw(identifier)
        return parse_message(raw, info['id'], info['threadId'], info.get('labelIds', []))

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
            part = flat[int(part_id)]
        except (ValueError, IndexError):
            raise MailError('附件不存在', 'not_found', 404)
        body = part.get('body', {})
        if body.get('attachmentId'):
            payload = self.run(self.users.messages().attachments().get(userId='me', messageId=identifier, id=body['attachmentId']))
            return unb64(payload['data']), part.get('filename') or 'attachment', part.get('mimeType', 'application/octet-stream')
        return extract_attachment(self.raw(identifier)[0], part_id)

    def thread(self, identifier):
        result = self.run(self.users.threads().get(userId='me', id=identifier, format='minimal'))
        return [self.message(m['id']) for m in result.get('messages', [])]

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
        for thread in result.get('threads', []):
            detail = self.run(self.users.threads().get(userId='me', id=thread['id'], format='metadata', metadataHeaders=['Subject', 'From', 'Date', 'To']))
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


class GmailImapSmtpProvider:
    FLAGS = {'INBOX': 'INBOX', 'SENT': '\\Sent', 'DRAFT': '\\Drafts', 'TRASH': '\\Trash', 'SPAM': '\\Junk', 'ALL': '\\All', 'STARRED': '\\Flagged', 'IMPORTANT': '\\Important'}

    def __init__(self, email, secret, save_secret=None):
        self.email, self.secret = email, secret
        self.imap = imaplib.IMAP4_SSL('imap.gmail.com', 993, ssl_context=ssl.create_default_context(), timeout=30)
        try:
            self.imap.login(email, secret['password'])
            capabilities = self._ok(self.imap.capability())
            self.caps = set(b' '.join(capabilities).decode().upper().split())
            if 'X-GM-EXT-1' not in self.caps:
                raise MailError('服务器不支持 Gmail 扩展', 'unsupported', 422)
            self.folders = self._folders()
        except Exception:
            self.close()
            raise

    def close(self):
        try:
            self.imap.logout()
        except Exception:
            pass

    def _ok(self, result):
        status, data = result
        if status != 'OK':
            raise MailError('Gmail 无法完成操作，请刷新后重试')
        return data

    def _folders(self):
        output = []
        for item in self._ok(self.imap.list()):
            if not item:
                continue
            match = re.match(rb'\((.*?)\) "[^"]*" (.*)', item)
            if not match:
                continue
            flags = match[1].decode().split()
            name = match[2].decode()
            if name.startswith('"'):
                name = name[1:-1].replace('\\"', '"').replace('\\\\', '\\')
            if '\\Noselect' not in flags:
                output.append({'wire': name, 'name': utf7_decode(name), 'flags': flags})
        return output

    def folder_name(self, identifier):
        if identifier == 'INBOX':
            return 'INBOX'
        flag = self.FLAGS.get(identifier)
        if flag:
            for folder in self.folders:
                if flag.lower() in [f.lower() for f in folder['flags']]:
                    return folder['wire']
            if identifier == 'STARRED':
                return self.folder_name('ALL')
            raise MailError('找不到 Gmail 系统文件夹', 'not_found', 404)
        if identifier.startswith('label:'):
            name = unb64(identifier[6:]).decode()
            if any(f['wire'] == name for f in self.folders):
                return name
        raise MailError('标签不存在', 'not_found', 404)

    def select(self, folder):
        self._ok(self.imap.select(quote(folder)))
        validity = self.imap.response('UIDVALIDITY')[1]
        return (validity[0] or b'0').decode()

    def labels(self):
        labels = []
        for folder in self.folders:
            identifier = next((key for key, flag in self.FLAGS.items() if flag.lower() in [f.lower() for f in folder['flags']]), None)
            if folder['wire'].upper() == 'INBOX':
                identifier = 'INBOX'
            labels.append({'id': identifier or 'label:' + b64(folder['wire'].encode()), 'name': folder['name'], 'type': 'system' if identifier else 'user'})
        return labels

    def label(self, action, identifier, name):
        if action == 'create':
            self._ok(self.imap.create(quote(utf7_encode(name))))
        else:
            if not identifier.startswith('label:'):
                raise MailError('不能修改系统标签', 'validation', 422)
            wire = self.folder_name(identifier)
            self._ok(self.imap.rename(quote(wire), quote(utf7_encode(name))) if action == 'rename' else self.imap.delete(quote(wire)))
        return {}

    def _search(self, *args):
        return (self._ok(self.imap.uid('SEARCH', None, *args))[0] or b'').split()

    def _fetch(self, uid, body=True):
        result = self._ok(self.imap.uid('FETCH', uid, '(UID X-GM-MSGID X-GM-THRID X-GM-LABELS FLAGS BODY.PEEK[])' if body else '(UID X-GM-MSGID X-GM-THRID X-GM-LABELS FLAGS BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE TO)])'))
        for row in result:
            if isinstance(row, tuple):
                header, raw = row
                msgid, thread = re.search(rb'X-GM-MSGID (\d+)', header), re.search(rb'X-GM-THRID (\d+)', header)
                if not msgid or not thread:
                    continue
                labels = []
                flags = re.search(rb'FLAGS \((.*?)\)', header)
                flagdata = flags[1] if flags else b''
                if b'\\Seen' not in flagdata:
                    labels.append('UNREAD')
                if b'\\Flagged' in flagdata:
                    labels.append('STARRED')
                if b'\\Draft' in flagdata:
                    labels.append('DRAFT')
                match = re.search(rb'X-GM-LABELS \((.*?)\)', header)
                if match:
                    tokens = re.findall(rb'"((?:\\.|[^"\\])*)"|([^\s]+)', match[1])
                    for quoted, plain in tokens:
                        token = (quoted or plain).decode().replace('\\\\', '\\').replace('\\"', '"')
                        mapped = {'\\Inbox': 'INBOX', '\\Sent': 'SENT', '\\Drafts': 'DRAFT', '\\Trash': 'TRASH', '\\Spam': 'SPAM', '\\Starred': 'STARRED', '\\Important': 'IMPORTANT'}.get(token)
                        labels.append(mapped or 'label:' + b64(token.encode()))
                return raw, msgid[1].decode(), thread[1].decode(), list(set(labels))
        raise MailError('邮件已不存在，请刷新', 'not_found', 404)

    def locate(self, identifier):
        if not identifier.isdecimal():
            raise MailError('无效邮件标识', 'validation', 422)
        for key in ('ALL', 'TRASH', 'SPAM'):
            folder = self.folder_name(key)
            validity = self.select(folder)
            ids = self._search('X-GM-MSGID', identifier)
            if ids:
                return folder, validity, ids[0]
        raise MailError('邮件不存在', 'not_found', 404)

    def raw(self, identifier):
        self.locate(identifier)
        uid = self._search('X-GM-MSGID', identifier)[0]
        return self._fetch(uid)

    def message(self, identifier):
        raw, mid, tid, labels = self.raw(identifier)
        return parse_message(raw, mid, tid, labels)

    def attachment(self, identifier, part_id):
        return extract_attachment(self.raw(identifier)[0], part_id)

    def thread(self, identifier):
        if not identifier.isdecimal():
            raise MailError('无效会话标识', 'validation', 422)
        messages = {}
        for key in ('ALL', 'TRASH', 'SPAM'):
            self.select(self.folder_name(key))
            for uid in self._search('X-GM-THRID', identifier):
                raw, mid, tid, labels = self._fetch(uid)
                messages[mid] = parse_message(raw, mid, tid, labels)
        return list(messages.values())

    def list(self, folder='INBOX', query='', cursor=''):
        self.select(self.folder_name(folder))
        if query:
            self.imap.literal = query.encode('utf-8')
            ids = self._search('X-GM-RAW')
        else:
            ids = self._search('FLAGGED' if folder == 'STARRED' else 'ALL')
        try:
            offset = int(cursor or 0)
            if offset < 0:
                raise ValueError()
        except ValueError:
            raise MailError('无效分页参数', 'validation', 422)
        ids.reverse()
        # Build a lightweight thread index before pagination, so a thread appears once.
        metadata = {}
        for start in range(0, len(ids), 250):
            rows = self._ok(self.imap.uid('FETCH', b','.join(ids[start:start + 250]), '(UID X-GM-THRID FLAGS)'))
            for row in rows:
                if not isinstance(row, bytes):
                    continue
                u, t = re.search(rb'UID (\d+)', row), re.search(rb'X-GM-THRID (\d+)', row)
                if u and t:
                    metadata[u[1]] = (t[1], b'\\Seen' not in row)
        groups = {}
        for uid in ids:
            if uid not in metadata:
                continue
            tid, unread = metadata[uid]
            group = groups.setdefault(tid, {'uid': uid, 'count': 0, 'unread': False})
            group['count'] += 1
            group['unread'] = group['unread'] or unread
        page = list(groups.values())[offset:offset + 30]
        items = []
        for group in page:
            raw, mid, tid, labels = self._fetch(group['uid'], body=False)
            if group['unread'] and 'UNREAD' not in labels:
                labels.append('UNREAD')
            item = parse_message(raw, mid, tid, labels)
            item['count'] = group['count']
            items.append(item)
        return {'items': items, 'nextCursor': str(offset + 30) if offset + 30 < len(groups) else ''}

    def thread_modify(self, identifiers, add, remove, action):
        mids = set()
        for identifier in identifiers:
            if not identifier.isdecimal():
                raise MailError('无效会话标识', 'validation', 422)
            for folder in ('ALL', 'TRASH', 'SPAM'):
                self.select(self.folder_name(folder))
                for uid in self._search('X-GM-THRID', identifier):
                    mids.add(self._fetch(uid, body=False)[1])
        if action == 'labels':
            self.modify(list(mids), add, remove)
        else:
            for mid in mids:
                self.move(mid, action == 'trash')

    def modify(self, identifiers, add, remove):
        for identifier in identifiers:
            _, _, uid = self.locate(identifier)
            for operation, labels in (('+', add), ('-', remove)):
                for label in labels:
                    if label in ('UNREAD', 'STARRED'):
                        flag = '\\Seen' if label == 'UNREAD' else '\\Flagged'
                        sign = ('-' if operation == '+' else '+') if label == 'UNREAD' else operation
                        self._ok(self.imap.uid('STORE', uid, sign + 'FLAGS.SILENT', '(' + flag + ')'))
                    else:
                        wire = {'INBOX': '\\Inbox', 'SPAM': '\\Spam', 'TRASH': '\\Trash', 'IMPORTANT': '\\Important'}.get(label)
                        wire = wire or self.folder_name(label)
                        self._ok(self.imap.uid('STORE', uid, operation + 'X-GM-LABELS.SILENT', '(' + quote(wire) + ')'))

    def move(self, identifier, trash=True):
        _, _, uid = self.locate(identifier)
        self._ok(self.imap.uid('COPY', uid, quote(self.folder_name('TRASH' if trash else 'INBOX'))))
        if not trash:
            self.modify([identifier], [], ['TRASH'])

    @contextmanager
    def smtp(self):
        port = self.secret.get('port', 465)
        connection = smtplib.SMTP_SSL('smtp.gmail.com', 465, context=ssl.create_default_context(), timeout=30) if port == 465 else smtplib.SMTP('smtp.gmail.com', 587, timeout=30)
        try:
            if port == 587:
                connection.ehlo()
                connection.starttls(context=ssl.create_default_context())
                connection.ehlo()
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
        return {'id': msg['Message-ID'], 'refused': list(refused)}

    def drafts(self):
        self.select(self.folder_name('DRAFT'))
        return [{'id': self._fetch(uid, False)[1]} for uid in reversed(self._search('ALL')[-100:])]

    def draft_get(self, identifier):
        result = self.message(identifier)
        if 'DRAFT' not in result['labels']:
            raise MailError('此邮件不是草稿', 'validation', 422)
        return result

    def draft_save(self, msg, identifier=None, thread_id=None):
        if identifier and 'UIDPLUS' not in self.caps:
            raise MailError('服务器不支持定向删除草稿', 'unsupported', 422)
        if identifier:
            self.draft_get(identifier)
        folder = self.folder_name('DRAFT')
        result = self._ok(self.imap.append(quote(folder), '(\\Draft)', None, msg.as_bytes()))
        appended = re.search(rb'APPENDUID (\d+) (\d+)', b' '.join(x for x in result if isinstance(x, bytes)))
        validity = self.select(folder)
        if not appended or appended[1].decode() != validity:
            raise MailError('草稿可能已保存，请刷新草稿箱后确认', 'uncertain', 409)
        mid = self._fetch(appended[2], False)[1]
        if identifier:
            self.draft_delete(identifier)
        return mid

    def draft_delete(self, identifier):
        self.draft_get(identifier)
        if 'UIDPLUS' not in self.caps:
            raise MailError('服务器不支持定向删除草稿', 'unsupported', 422)
        self.select(self.folder_name('DRAFT'))
        ids = self._search('X-GM-MSGID', identifier)
        if not ids:
            raise MailError('草稿已不存在，请刷新', 'not_found', 404)
        uid = ids[0]
        self._ok(self.imap.uid('STORE', uid, '+FLAGS.SILENT', '(\\Deleted)'))
        self._ok(self.imap.uid('EXPUNGE', uid))

    def sync(self, state, folder):
        validity = self.select(self.folder_name(folder))
        ids = self._search('ALL')
        fingerprint = [validity, (ids[-1] if ids else b'0').decode(), len(ids)]
        reset = folder in state and state[folder][0] != validity
        state[folder] = fingerprint
        # Flags/labels can change without UIDNEXT changing: invalidate the active view each poll.
        return state, True, reset
