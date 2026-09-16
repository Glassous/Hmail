"""Read MIME bodies without downloading attachment payloads."""
import base64
import quopri
import re
from email.message import Message

from .mail import MailError, parse_message, unb64


def text_decode(raw, charset='utf-8'):
    try:
        return raw.decode(charset or 'utf-8', errors='replace')
    except LookupError:
        return raw.decode('utf-8', errors='replace')


def gmail_message(provider, info):
    payload = info.get('payload', {})
    headers = ''.join(f"{h['name']}: {h['value']}\r\n" for h in payload.get('headers', [])) + '\r\n'
    result = parse_message(headers.encode(), info['id'], info['threadId'], info.get('labelIds', []))
    texts, html, attachments = [], [], []
    def walk(part):
        kind = part.get('mimeType', '')
        body = part.get('body', {})
        headers = {h['name'].lower(): h['value'] for h in part.get('headers', [])}
        name = part.get('filename', '')
        if name or headers.get('content-disposition', '').lower().startswith('attachment') or kind.startswith('image/'):
            attachments.append({'id': 'gmail:' + part.get('partId', ''), 'name': name or 'inline-image', 'size': body.get('size', 0), 'type': kind, 'cid': headers.get('content-id', '').strip('<>')})
        elif kind in ('text/plain', 'text/html'):
            raw = unb64(body.get('data', ''))
            if body.get('attachmentId'):
                raw = unb64(provider.run(provider.users.messages().attachments().get(userId='me', messageId=info['id'], id=body['attachmentId']))['data'])
            charset = Message()
            charset['Content-Type'] = headers.get('content-type', kind)
            (html if kind == 'text/html' else texts).append(text_decode(raw, charset.get_content_charset()))
        else:
            for child in part.get('parts', []):
                walk(child)
    walk(payload)
    result.update(text='\n'.join(texts), html='\n'.join(html), attachments=attachments, snippet=info.get('snippet', ''))
    return result


def parse_structure(raw):
    """IMAP S-expression parser, including quoted strings and literals."""
    position = 0
    def value():
        nonlocal position
        while position < len(raw) and raw[position:position+1].isspace():
            position += 1
        if position >= len(raw):
            raise ValueError('incomplete BODYSTRUCTURE')
        char = raw[position:position+1]
        position += 1
        if char == b'(':
            values = []
            while True:
                while position < len(raw) and raw[position:position+1].isspace(): position += 1
                if raw[position:position+1] == b')':
                    position += 1
                    return values
                values.append(value())
        if char == b'"':
            output = bytearray()
            while position < len(raw):
                char = raw[position:position+1]; position += 1
                if char == b'"': return output.decode('utf-8', errors='replace')
                if char == b'\\': char = raw[position:position+1]; position += 1
                output.extend(char)
            raise ValueError('incomplete quoted string')
        if char == b'{':
            end = raw.index(b'}\r\n', position)
            length = int(raw[position:end])
            position = end + 3
            data = raw[position:position+length]; position += length
            return data.decode('utf-8', errors='replace')
        start = position - 1
        while position < len(raw) and raw[position:position+1] not in b' ()\r\n': position += 1
        atom = raw[start:position].decode()
        return None if atom.upper() == 'NIL' else atom
    return value()


def descriptors(structure, prefix=''):
    if not isinstance(structure, list) or len(structure) < 2:
        raise ValueError('invalid BODYSTRUCTURE')
    if isinstance(structure[0], list):
        result = []
        for index, child in enumerate(structure):
            if not isinstance(child, list): break
            result.extend(descriptors(child, f'{prefix}.{index+1}' if prefix else str(index+1)))
        return result
    kind = f'{structure[0]}/{structure[1]}'.lower()
    params = structure[2] if isinstance(structure[2], list) else []
    params = {str(params[i]).lower(): params[i+1] for i in range(0, len(params)-1, 2)}
    disposition_index = 9 if kind.startswith('text/') else 11 if kind == 'message/rfc822' else 8
    disposition = structure[disposition_index] if len(structure) > disposition_index else None
    name = params.get('name', '')
    attached = False
    if isinstance(disposition, list):
        attached = str(disposition[0]).lower() == 'attachment'
        parameters = disposition[1] if len(disposition) > 1 and isinstance(disposition[1], list) else []
        for i in range(0, len(parameters)-1, 2):
            if str(parameters[i]).lower() == 'filename': name = parameters[i+1]
    return [{'section': prefix or 'TEXT', 'id': 'mime:' + (prefix or 'TEXT'), 'type': kind, 'name': name or '', 'charset': params.get('charset', 'utf-8'), 'encoding': str(structure[5]).lower(), 'size': int(structure[6] or 0), 'cid': str(structure[3] or '').strip('<>'), 'attachment': bool(name or attached or kind.startswith('image/') or kind == 'message/rfc822')}]


def imap_parts(provider, identifier):
    parts = provider._decode(identifier)
    if len(parts) != 4 or parts[0] != 'm':
        raise MailError('无效邮件标识', 'validation', 422)
    wire, validity, uid = parts[1], str(parts[2]), str(parts[3])
    provider._select(wire, validity)
    response = provider._ok(provider.imap.uid('FETCH', uid, '(UID FLAGS BODYSTRUCTURE)'))
    raw = b' '.join((item[0] + b'\r\n' + item[1]) if isinstance(item, tuple) else item for item in response if isinstance(item, (bytes, tuple)))
    match = re.search(rb'BODYSTRUCTURE\s+(\()', raw, re.I)
    if not match:
        raise MailError('邮件已不存在，请刷新', 'not_found', 404)
    try:
        return wire, validity, uid, descriptors(parse_structure(raw[match.start(1):]))
    except (ValueError, IndexError, TypeError):
        raise MailError('邮件 MIME 结构无法解析', 'mime_error', 502) from None


def imap_part_bytes(provider, uid, part):
    rows = provider._fetch([uid.encode()], f"(UID BODY.PEEK[{part['section']}])".encode())
    if not rows:
        raise MailError('邮件内容不存在', 'not_found', 404)
    raw = rows[0][2]
    if part['encoding'] == 'base64':
        return base64.b64decode(raw)
    if part['encoding'] == 'quoted-printable':
        return quopri.decodestring(raw)
    return raw


def imap_message(provider, identifier):
    wire, validity, uid, parts = imap_parts(provider, identifier)
    rows = provider._fetch([uid.encode()], b'(UID FLAGS BODY.PEEK[HEADER])')
    if not rows:
        raise MailError('邮件已不存在', 'not_found', 404)
    msg = provider._parse(rows[0][2])
    kind, key = provider._thread_key(msg)
    result = parse_message(rows[0][2], identifier, provider._encode('t', wire, validity, uid, kind, key), provider._labels(rows[0][1], wire))
    texts, html, attachments = [], [], []
    for part in parts:
        if part['attachment']:
            attachments.append({k: part[k] for k in ('id', 'name', 'size', 'type', 'cid')})
        elif part['type'] in ('text/plain', 'text/html'):
            value = text_decode(imap_part_bytes(provider, uid, part), part['charset'])
            (html if part['type'] == 'text/html' else texts).append(value)
    result.update(text='\n'.join(texts), html='\n'.join(html), attachments=attachments)
    result['snippet'] = (result['text'] or re.sub('<[^>]+>', ' ', result['html']))[:200]
    return result
