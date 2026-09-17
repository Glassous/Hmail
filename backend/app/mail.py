import base64
import hashlib
import hmac
import html
import mimetypes
import os
import re
import secrets
from email import policy
from email.message import EmailMessage
from email.parser import BytesParser
from email.utils import getaddresses, make_msgid
from urllib.parse import quote as urlquote

import nh3

MAX_ATTACHMENT = 18 * 1024 * 1024
SIGN_KEY = os.environ.get('TOKEN_ENCRYPTION_KEY', 'hmail-token-key-default').encode()


def sign_url(value: str) -> str:
    return hmac.new(SIGN_KEY, value.encode('utf-8'), hashlib.sha256).hexdigest()[:32]


def verify_url_sig(value: str, sig: str) -> bool:
    if not sig or not value:
        return False
    return secrets.compare_digest(sign_url(value), sig)


class MailError(Exception):
    def __init__(self, message, code='mail_error', status=502):
        super().__init__(message)
        self.message, self.code, self.status = message, code, status


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def unb64(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def safe_html(value, remote=True, aid='', mid='', attachments=None):
    if not value:
        return ''

    # 1. Map CID inline images to backend attachment URLs with HMAC signature
    cid_map = {}
    if aid and mid and attachments:
        for att in attachments:
            cid = str(att.get('cid') or '').strip('<>').strip()
            if cid:
                att_part = att['id']
                att_sig = sign_url(f'{aid}:{mid}:{att_part}')
                att_url = f"/api/v1/gmail-accounts/{aid}/messages/{mid}/attachments/{att_part}?sig={att_sig}"
                cid_map[cid] = att_url
                cid_map[cid.lower()] = att_url

    if cid_map:
        value = re.sub(
            r'''(?i)\b(src|background)=["']?cid:([^"'\s>]+)["']?''',
            lambda m: f'{m.group(1)}="{cid_map.get(m.group(2).strip("<>"), cid_map.get(m.group(2).strip("<>").lower(), m.group(0)))}"',
            value
        )
        value = re.sub(
            r'''(?i)url\(\s*['"]?cid:([^"')\s]+)['"]?\s*\)''',
            lambda m: f'url("{cid_map.get(m.group(1).strip("<>"), cid_map.get(m.group(1).strip("<>").lower(), m.group(0)))}")',
            value
        )

    # 2. Extract and sanitize style blocks
    style_blocks = re.findall(r'<style\b[^>]*>(.*?)</style>', value, flags=re.DOTALL | re.IGNORECASE)
    clean_styles = []
    for s in style_blocks:
        s = re.sub(r'(?i)expression\s*\(.*?\)', '', s)
        s = re.sub(r'(?i)javascript:', '', s)
        s = re.sub(r'(?i)@import\b[^;]*;', '', s)
        if not remote:
            s = re.sub(r'(?i)url\s*\(\s*[\'"]?https?:[^\)]*\)', 'none', s)
        else:
            def proxy_style_url(m):
                raw = html.unescape(m.group(1).strip('\'" \t'))
                if raw.startswith('//'):
                    raw = 'https:' + raw
                if raw.startswith(('http://', 'https://')):
                    sig = sign_url(raw)
                    return f"url('/api/v1/proxy/image?url={urlquote(raw, safe='')}&sig={sig}')"
                return m.group(0)
            s = re.sub(r'''(?i)url\(\s*['"]?((?:https?:|//)[^"')\s]+)['"]?\s*\)''', proxy_style_url, s)
        clean_styles.append(s)

    tags = set(nh3.ALLOWED_TAGS) | {'center', 'font'}
    attrs = {
        '*': {'style', 'class', 'id', 'dir', 'align', 'valign', 'bgcolor', 'color', 'width', 'height', 'title', 'lang'},
        'a': {'href', 'title', 'target'},
        'td': {'colspan', 'rowspan', 'headers', 'width', 'height', 'align', 'valign', 'bgcolor', 'style', 'class'},
        'th': {'colspan', 'rowspan', 'headers', 'width', 'height', 'align', 'valign', 'bgcolor', 'style', 'class'},
        'table': {'width', 'height', 'align', 'valign', 'bgcolor', 'border', 'cellpadding', 'cellspacing', 'style', 'class'},
        'img': {'src', 'alt', 'width', 'height', 'title', 'border', 'align', 'style', 'class', 'loading', 'srcset'},
        'font': {'color', 'size', 'face'},
    }
    clean = nh3.clean(value, tags=tags, attributes=attrs, url_schemes={'https', 'http', 'mailto', 'cid', 'data'}, strip_comments=True)

    # 3. Rewrite external image URLs to backend proxy
    def to_proxy_url(raw_url):
        raw = html.unescape(raw_url.strip())
        if raw.startswith('//'):
            raw = 'https:' + raw
        if raw.startswith(('http://', 'https://')):
            sig = sign_url(raw)
            return f"/api/v1/proxy/image?url={urlquote(raw, safe='')}&sig={sig}"
        return raw

    if remote:
        clean = re.sub(
            r'''(?i)\b(src|background)=["']([^"']+)["']''',
            lambda m: f'{m.group(1)}="{to_proxy_url(m.group(2))}"' if m.group(2).startswith(('http://', 'https://', '//')) else m.group(0),
            clean
        )
        clean = re.sub(
            r'''(?i)url\(\s*['"]?((?:https?:|//)[^"')\s]+)['"]?\s*\)''',
            lambda u: f"url('{to_proxy_url(u.group(1))}')",
            clean
        )

        def rewrite_srcset(m):
            parts = m.group(1).split(',')
            new_parts = []
            for p in parts:
                p_strip = p.strip()
                if not p_strip:
                    continue
                tokens = p_strip.split()
                if tokens and tokens[0].startswith(('http://', 'https://', '//')):
                    tokens[0] = to_proxy_url(tokens[0])
                new_parts.append(' '.join(tokens))
            return f'srcset="{", ".join(new_parts)}"'
        clean = re.sub(r'''(?i)\bsrcset=["']([^"']+)["']''', rewrite_srcset, clean)
    else:
        clean = re.sub(
            r'''(?i)\b(src)=["'](?:https?:|//)[^"']+["']''',
            r'\1="data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' width=\'1\' height=\'1\'%3E%3C/svg%3E"',
            clean
        )
        clean = re.sub(
            r'''(?i)url\(\s*['"]?(?:https?:|//)[^"')\s]+['"]?\s*\)''',
            'none',
            clean
        )

    # 4. Enforce strict CSP in iframe: ONLY 'self' and data: allowed
    csp = "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; font-src data:;"
    styles_markup = ''.join(f'<style>{s}</style>' for s in clean_styles)
    return (
        '<!doctype html><html><head><meta charset="utf-8">'
        f'<meta http-equiv="Content-Security-Policy" content="{csp}">'
        '<meta name="referrer" content="no-referrer">'
        '<base target="_blank">'
        '<style>'
        'body{font:14px/1.7 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#263238;overflow-wrap:anywhere;margin:16px;word-break:break-word}'
        'img{max-width:100%;height:auto}'
        'table{max-width:100%}'
        'pre{white-space:pre-wrap;word-break:break-all}'
        'a{color:#1967d2;text-decoration:underline}'
        '</style>'
        f'{styles_markup}'
        '</head><body>'
        f'{clean}'
        '</body></html>'
    )


def parse_message(raw, message_id, thread_id, labels=None):
    msg = BytesParser(policy=policy.default).parsebytes(raw)
    text_parts, html_parts, attachments = [], [], []
    for index, part in enumerate(msg.walk()):
        if part.is_multipart():
            continue
        content = part.get_payload(decode=True) or b''
        name = part.get_filename()
        attached = part.get_content_disposition() == 'attachment'
        # Non-Gmail mailboxes often keep a `name`/`filename` on the text body part; only an
        # explicit attachment disposition (or a non-text part) turns it into an attachment.
        body_part = part.get_content_type() in ('text/plain', 'text/html') and not attached
        if attached or part.get_content_type().startswith('image/') or part.get_content_type() == 'message/rfc822' or (name and not body_part):
            attachments.append({'id': str(index), 'name': name or 'inline-image', 'size': len(content), 'type': part.get_content_type(), 'cid': str(part.get('Content-ID', '')).strip('<>')})
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
    fields = [data.get(name, '').strip() for name in ('to', 'cc', 'bcc') if data.get(name, '').strip()]
    addresses = [a for _, a in getaddresses(fields)]
    if not addresses or any('@' not in a or '\r' in a or '\n' in a for a in addresses):
        raise MailError('请填写有效的收件人地址', 'validation', 422)
    return addresses
