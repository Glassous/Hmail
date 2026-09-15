import base64
import html
import mimetypes
import re
from email import policy
from email.message import EmailMessage
from email.parser import BytesParser
from email.utils import getaddresses, make_msgid

import nh3

MAX_ATTACHMENT = 18 * 1024 * 1024


class MailError(Exception):
    def __init__(self, message, code='mail_error', status=502):
        super().__init__(message)
        self.message, self.code, self.status = message, code, status


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def unb64(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def safe_html(value, remote=False):
    tags = {'p', 'div', 'span', 'br', 'hr', 'strong', 'b', 'em', 'i', 'u', 's', 'blockquote', 'pre', 'code', 'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tr', 'td', 'th', 'h1', 'h2', 'h3', 'h4', 'a'}
    attrs = {'a': {'href', 'title'}, 'td': {'colspan', 'rowspan'}, 'th': {'colspan', 'rowspan'}}
    if remote:
        tags.add('img')
        attrs['img'] = {'src', 'alt', 'width', 'height'}
    clean = nh3.clean(value, tags=tags, attributes=attrs, url_schemes={'https', 'http', 'mailto'}, strip_comments=True)
    csp = "default-src 'none'; style-src 'unsafe-inline'; img-src https: http:" if remote else "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'"
    return '<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="' + csp + '"><meta name="referrer" content="no-referrer"><style>body{font:14px/1.7 system-ui;color:#263238;overflow-wrap:anywhere;margin:16px}img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}a{color:#1967d2}</style></head><body>' + clean + '</body></html>'


def parse_message(raw, message_id, thread_id, labels=None):
    msg = BytesParser(policy=policy.default).parsebytes(raw)
    text_parts, html_parts, attachments = [], [], []
    for index, part in enumerate(msg.walk()):
        if part.is_multipart():
            continue
        content = part.get_payload(decode=True) or b''
        if part.get_filename() or part.get_content_disposition() == 'attachment' or part.get_content_type().startswith('image/'):
            attachments.append({'id': str(index), 'name': part.get_filename() or 'inline-image', 'size': len(content), 'type': part.get_content_type(), 'cid': str(part.get('Content-ID', '')).strip('<>')})
        elif part.get_content_type() in ('text/plain', 'text/html'):
            try:
                decoded = content.decode(part.get_content_charset() or 'utf-8', errors='replace')
            except LookupError:
                decoded = content.decode('utf-8', errors='replace')
            (html_parts if part.get_content_type() == 'text/html' else text_parts).append(decoded)
    body = '\n'.join(text_parts)
    markup = '\n'.join(html_parts)
    return {'id': str(message_id), 'threadId': str(thread_id), 'subject': str(msg.get('Subject', '(无主题)')), 'from': str(msg.get('From', '')), 'to': str(msg.get('To', '')), 'cc': str(msg.get('Cc', '')), 'bcc': str(msg.get('Bcc', '')), 'date': str(msg.get('Date', '')), 'messageId': str(msg.get('Message-ID', '')), 'references': str(msg.get('References', '')), 'labels': labels or [], 'snippet': (body or re.sub('<[^>]+>', ' ', markup))[:200], 'text': body, 'html': markup, 'attachments': attachments}


def extract_attachment(raw, part_id):
    msg = BytesParser(policy=policy.default).parsebytes(raw)
    parts = list(msg.walk())
    try:
        part = parts[int(part_id)]
        if part.is_multipart() or not (part.get_filename() or part.get_content_disposition() == 'attachment' or part.get_content_type().startswith('image/')):
            raise ValueError()
    except (ValueError, IndexError):
        raise MailError('附件不存在', 'not_found', 404)
    return part.get_payload(decode=True) or b'', part.get_filename() or 'attachment', part.get_content_type()


def build_message(sender, data, attachments):
    msg = EmailMessage(policy=policy.SMTP)
    for key in ('to', 'cc', 'bcc', 'subject', 'inReplyTo', 'references'):
        if '\r' in data.get(key, '') or '\n' in data.get(key, ''):
            raise MailError('邮件头不能包含换行', 'validation', 422)
    msg['From'] = sender
    for key in ('to', 'cc', 'bcc', 'subject'):
        if data.get(key):
            msg[key.title()] = data[key]
    msg['Message-ID'] = data.get('messageId') or make_msgid()
    if data.get('inReplyTo'):
        msg['In-Reply-To'] = data['inReplyTo']
    if data.get('references'):
        msg['References'] = data['references']
    msg.set_content(data.get('text', ''))
    for name, content, content_type in attachments:
        content_type = content_type or mimetypes.guess_type(name)[0] or 'application/octet-stream'
        if '/' not in content_type:
            content_type = 'application/octet-stream'
        main, sub = content_type.split('/', 1)
        msg.add_attachment(content, maintype=main, subtype=sub, filename=name)
    return msg


def recipients(data):
    addresses = [a for _, a in getaddresses([data.get('to', ''), data.get('cc', ''), data.get('bcc', '')])]
    if not addresses or any('@' not in a or '\r' in a or '\n' in a for a in addresses):
        raise MailError('请填写有效的收件人地址', 'validation', 422)
    return addresses
