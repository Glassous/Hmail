"""Bounded metadata batches for Gmail and standard IMAP, with resumable cursors."""
import json
import re
import uuid

from googleapiclient.errors import HttpError

from .mail import MailError
from .providers import HEADER_FIELDS, ImapSmtpProvider


def gmail_metadata(provider, identifiers):
    output = []
    for start in range(0, len(identifiers), 20):
        responses, errors = {}, []
        def receive(identifier, response, exception):
            if exception:
                if isinstance(exception, HttpError) and exception.resp.status == 404:
                    responses[identifier] = None
                else:
                    errors.append(exception)
            else:
                responses[identifier] = response
        batch = provider.service.new_batch_http_request(callback=receive)
        for identifier in identifiers[start:start + 20]:
            batch.add(provider.users.messages().get(userId='me', id=identifier, format='metadata', metadataHeaders=['Subject', 'From', 'To', 'Date', 'Message-ID', 'References']), request_id=identifier)
        batch.execute()
        if errors:
            raise errors[0]
        for identifier, result in responses.items():
            if result is None:
                output.append({'id': identifier, 'deleted': True})
                continue
            headers = {h['name'].lower(): h['value'] for h in result.get('payload', {}).get('headers', [])}
            output.append({'id': result['id'], 'threadId': result['threadId'], 'subject': headers.get('subject', '(无主题)'), 'from': headers.get('from', ''), 'to': headers.get('to', ''), 'date': headers.get('date', ''), 'messageId': headers.get('message-id', ''), 'references': headers.get('references', ''), 'labels': result.get('labelIds', []), 'snippet': result.get('snippet', ''), 'internalDate': result.get('internalDate', '0')})
    return output


def gmail_batch(provider, folder, state, known):
    state = dict(state)
    reset = False
    deleted = []
    delta = bool(state.get('historyId')) and not state.get('page')
    if delta:
        try:
            history = provider.run(provider.users.history().list(userId='me', startHistoryId=state['historyId'], pageToken=state.get('historyPage'), maxResults=100))
            ids = list(dict.fromkeys(m['id'] for change in history.get('history', []) for m in change.get('messages', [])))
            # A single history entry can contain many IDs. Never lose overflow.
            ids = state.get('pendingIds', []) or ids
            next_ids = ids[200:]
            records = gmail_metadata(provider, ids[:200]) if ids else []
            state['pendingIds'] = next_ids
            done = not next_ids and not history.get('nextPageToken')
            if not next_ids:
                state['historyPage'] = history.get('nextPageToken')
                if done:
                    state['historyId'] = history['historyId']
        except (HttpError, MailError) as exc:
            status = exc.resp.status if isinstance(exc, HttpError) else exc.status
            if status != 404:
                raise
            state, delta, reset = {}, False, True
    if not delta:
        if not state.get('scan'):
            state['scan'] = uuid.uuid4().hex
            state['baseline'] = provider.profile()['historyId']
        args = {'userId': 'me', 'maxResults': 200, 'includeSpamTrash': True}
        if folder != 'ALL':
            args['labelIds'] = [folder]
        if state.get('page'):
            args['pageToken'] = state['page']
        result = provider.run(provider.users.messages().list(**args))
        records = gmail_metadata(provider, [m['id'] for m in result.get('messages', [])])
        state['page'] = result.get('nextPageToken', '')
        done = not state['page']
        if done:
            state['historyId'] = state['baseline']
    drafts = {}
    if folder == 'DRAFT':
        token = None
        while True:
            result = provider.run(provider.users.drafts().list(userId='me', maxResults=500, pageToken=token))
            drafts.update({d['message']['id']: d['id'] for d in result.get('drafts', [])})
            token = result.get('nextPageToken')
            if not token:
                break
    items = []
    for item in records:
        if item.get('deleted') or (folder != 'ALL' and folder not in item.get('labels', [])):
            deleted.append(item['id'])
        else:
            if item['id'] in drafts:
                item['draftId'] = drafts[item['id']]
            items.append(item)
    return {'items': items, 'deleted': deleted, 'state': state, 'done': done, 'sweep': done and not delta, 'reset': reset}


def imap_batch(provider, folder, state, known):
    state = dict(state)
    wire = provider.folder_wire(folder)
    validity = provider._select(wire)
    reset = bool(state.get('validity')) and state['validity'] != validity
    if reset:
        state, known = {}, {}
    delta = bool(state.get('delta'))
    if state.get('finished') and state.get('modseq') not in (None, '0') and getattr(provider, 'modseq', '0') != '0':
        all_uids = provider._search('FLAGGED' if folder == 'STARRED' else 'ALL')
        current = {provider._encode('m', wire, validity, uid.decode()): uid.decode() for uid in all_uids}
        existing = set(known.all_ids())
        response = provider._ok(provider.imap.uid('FETCH', '1:*', '(UID FLAGS)', f"(CHANGEDSINCE {int(state['modseq'])})")) if all_uids else []
        changed = set()
        for raw in response:
            if isinstance(raw, bytes):
                match = re.search(rb'UID (\d+)', raw)
                if match: changed.add(match[1].decode())
        state = {'scan': state['scan'], 'validity': validity, 'delta': True, 'targetModseq': provider.modseq,
                 'deltaUids': sorted(changed.intersection(current.values()) | {current[mid] for mid in current.keys() - existing}, key=int, reverse=True),
                 'deleteIds': sorted(existing - current.keys())}
        delta = True
    elif not state.get('scan') or state.get('finished'):
        state = {'scan': uuid.uuid4().hex, 'validity': validity, 'before': 0, 'targetModseq': getattr(provider, 'modseq', '0')}
    criteria = ['FLAGGED'] if folder == 'STARRED' else ['ALL']
    if state.get('before'):
        criteria = ['UID', f"1:{int(state['before']) - 1}"] + (['FLAGGED'] if folder == 'STARRED' else [])
    ids = [uid.encode() for uid in state['deltaUids']] if delta else list(reversed(provider._search(*criteria)))
    window = ids[:200]
    flags = {}
    if window:
        result = provider._ok(provider.imap.uid('FETCH', b','.join(window), '(UID FLAGS)'))
        for raw in result:
            if not isinstance(raw, bytes):
                continue
            match = re.search(rb'UID (\d+)', raw)
            labels = re.search(rb'FLAGS \((.*?)\)', raw)
            if match:
                flags[match[1].decode()] = labels[1] if labels else b''
    missing = [uid for uid in window if provider._encode('m', wire, validity, uid.decode()) not in known]
    headers = {uid: (flag, raw) for uid, flag, raw in provider._fetch(missing, HEADER_FIELDS)}
    items = []
    for uid_bytes in window:
        uid = uid_bytes.decode()
        identifier = provider._encode('m', wire, validity, uid)
        if uid not in flags:
            continue
        if identifier in known:
            item = dict(known[identifier])
        elif uid in headers:
            msg = provider._parse(headers[uid][1])
            kind, thread_key = provider._thread_key(msg)
            item = {'id': identifier, 'threadId': provider._encode('t', wire, validity, uid, kind, thread_key), 'subject': str(msg.get('Subject') or '(无主题)'), 'from': str(msg.get('From') or ''), 'to': str(msg.get('To') or ''), 'date': str(msg.get('Date') or ''), 'messageId': str(msg.get('Message-ID') or ''), 'references': str(msg.get('References') or ''), 'snippet': ''}
        else:
            continue
        item['labels'] = provider._labels(flags[uid], wire)
        if folder == 'DRAFT':
            item['draftId'] = identifier
        items.append(item)
    done = len(ids) <= 200
    if window:
        state['before'] = int(window[-1])
    # UID 1 is the final page: UID ranges with zero must never be issued.
    done = done or state.get('before') == 1
    deleted = []
    if delta:
        state['deltaUids'] = state['deltaUids'][200:]
        deleted, state['deleteIds'] = state['deleteIds'][:200], state['deleteIds'][200:]
        done = not state['deltaUids'] and not state['deleteIds']
        if done:
            state.pop('delta', None)
    if done:
        state['modseq'] = state.get('targetModseq', '0')
    state['finished'] = done
    return {'items': items, 'deleted': deleted, 'state': state, 'done': done, 'sweep': done and not delta, 'reset': reset}


def fetch_batch(provider, folder, state, known):
    return imap_batch(provider, folder, state, known) if isinstance(provider, ImapSmtpProvider) else gmail_batch(provider, folder, state, known)
