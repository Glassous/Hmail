"""Persistent metadata index. Provider work happens outside database transactions."""
import hashlib
import json
import os
import time
import uuid
from email.utils import parsedate_to_datetime

from sqlalchemy import select, update, delete, or_, and_
from sqlalchemy.exc import IntegrityError

from .core import Session, Account, MailFolder, MailSummary, MailThread, SyncJob, cache
from .mail import MailError, b64, unb64

ENABLED = os.getenv('MAIL_INDEX_ENABLED', 'true').lower() == 'true'
JOBS_ENABLED = os.getenv('MAIL_SYNC_JOBS_ENABLED', 'true').lower() == 'true'


def key(*parts):
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False).encode()).hexdigest()


def group_id(item):
    identifier = item['threadId']
    try:
        parts = json.loads(unb64(identifier))
        if parts[0] == 't':
            return key(parts[1], parts[2], parts[4], parts[5])
    except (ValueError, TypeError, IndexError):
        pass
    return identifier


def sort_date(item):
    try:
        return float(item.get('internalDate', 0)) / 1000 or parsedate_to_datetime(item['date']).timestamp()
    except (ValueError, TypeError, KeyError, OverflowError):
        return 0


def ensure_folder(db, aid, folder, label=None):
    identifier = key(aid, folder)
    row = db.get(MailFolder, identifier)
    if not row:
        row = MailFolder(key=identifier, account_id=aid, folder=folder, label=json.dumps(label or {'id': folder, 'name': folder, 'type': 'system'}))
        db.add(row)
        db.flush()
    elif label:
        row.label = json.dumps(label)
    return row


def enqueue(aid, folder='INBOX', priority=0, touch=False):
    identifier = key(aid, folder)
    with Session() as db:
        existing = db.get(SyncJob, identifier)
        if existing and existing.status in ('queued', 'running') and existing.priority >= priority:
            return {'jobId': identifier, 'status': existing.status}
    # The account row provides a short transaction-only lock for inserts and unlink.
    with Session.begin() as db:
        account = db.scalar(select(Account).where(Account.id == aid).with_for_update())
        if not account:
            raise MailError('邮箱不存在', 'not_found', 404)
        if touch:
            account.accessed_at = time.time()
        ensure_folder(db, aid, folder)
        job = db.get(SyncJob, identifier)
        if not job:
            job = SyncJob(id=identifier, account_id=aid, folder=folder, priority=priority)
            db.add(job)
        elif job.status != 'running':
            job.status, job.available_at = 'queued', 0
            job.priority = max(job.priority, priority)
            job.error = ''
        return {'jobId': identifier, 'status': job.status or 'queued'}


def state(aid, folder):
    with Session() as db:
        row = db.get(MailFolder, key(aid, folder))
        job = db.get(SyncJob, key(aid, folder))
        return {'status': job.status if job else 'queued', 'jobId': job.id if job else None,
                'indexVersion': row.version if row else 0, 'historyComplete': row.complete if row else False,
                'lastSyncedAt': row.synced_at if row else 0, 'error': job.error if job else ''}


def list_threads(aid, folder, cursor=''):
    folder_key = key(aid, folder)
    with Session() as db:
        pair = db.execute(select(MailFolder, SyncJob).outerjoin(SyncJob, SyncJob.id == MailFolder.key).where(MailFolder.key == folder_key)).first()
        if not pair:
            enqueue(aid, folder, 10, True)
            return {'items': [], 'nextCursor': '', 'sync': state(aid, folder)}
        row, job = pair
        version = row.version
        sync_state = {'status': job.status if job else 'queued', 'jobId': job.id if job else None, 'indexVersion': version, 'historyComplete': row.complete, 'lastSyncedAt': row.synced_at, 'error': job.error if job else ''}
        query = select(MailThread).where(MailThread.folder_key == folder_key)
        if cursor:
            try:
                token = json.loads(unb64(cursor))
                if token['folder'] != folder_key or token['version'] != version:
                    raise MailError('列表已更新，正在重新加载', 'cursor_expired', 409)
                date, identifier = float(token['date']), str(token['key'])
                query = query.where(or_(MailThread.sort_at < date, and_(MailThread.sort_at == date, MailThread.key < identifier)))
            except (ValueError, TypeError, KeyError):
                raise MailError('无效分页参数', 'validation', 422) from None
        page_key = 'index-page:' + key(folder_key, version, cursor)
        cached_page = cache.get(page_key)
        if cached_page:
            result = json.loads(cached_page)
            result['sync'] = sync_state
            return result
        rows = db.scalars(query.order_by(MailThread.sort_at.desc(), MailThread.key.desc()).limit(31)).all()
        next_cursor = ''
        if len(rows) > 30:
            last = rows[29]
            next_cursor = b64(json.dumps({'folder': folder_key, 'version': version, 'date': last.sort_at, 'key': last.key}).encode())
        result = {'items': [json.loads(r.data) for r in rows[:30]], 'nextCursor': next_cursor}
        cache.setex(page_key, 30, json.dumps(result))
    result['sync'] = sync_state
    return result


def labels(aid):
    with Session() as db:
        rows = db.scalars(select(MailFolder).where(MailFolder.account_id == aid)).all()
        return [json.loads(row.label) for row in rows]


def rebuild_threads(db, folder_key, groups):
    for group in groups:
        identifier = key(folder_key, group)
        rows = db.scalars(select(MailSummary).where(MailSummary.folder_key == folder_key, MailSummary.thread_id == group).order_by(MailSummary.sort_at.desc(), MailSummary.key.desc())).all()
        aggregate = db.get(MailThread, identifier)
        if not rows:
            if aggregate:
                db.delete(aggregate)
            continue
        data = json.loads(rows[0].data)
        data['count'] = len(rows)
        data['labels'] = sorted({label for row in rows for label in json.loads(row.data).get('labels', [])})
        if not aggregate:
            aggregate = MailThread(key=identifier, folder_key=folder_key, thread_id=group, sort_at=rows[0].sort_at, data='{}')
            db.add(aggregate)
        aggregate.sort_at, aggregate.data = rows[0].sort_at, json.dumps(data)


def commit_batch(job_id, owner, account, batch):
    with Session.begin() as db:
        current = db.scalar(select(Account).where(Account.id == account.id).with_for_update())
        job = db.get(SyncJob, job_id)
        if not current or not job or job.owner != owner or job.lease_until < time.time():
            return False
        if current.credential_version != account.credential_version or current.write_revision != account.write_revision:
            job.status, job.owner, job.available_at = 'queued', '', time.time()
            return False
        row = db.get(MailFolder, job_id)
        checkpoint = batch['state']
        groups = set()
        changed = False
        if batch.get('reset'):
            db.execute(delete(MailSummary).where(MailSummary.folder_key == job_id))
            db.execute(delete(MailThread).where(MailThread.folder_key == job_id))
            row.complete = False
            changed = True
        for item in batch['items']:
            # Whitelist: never persist body, MIME, credentials, or attachment bytes.
            data = {k: item[k] for k in ('id', 'threadId', 'subject', 'from', 'to', 'date', 'messageId', 'references', 'labels', 'snippet', 'draftId', 'internalDate') if k in item}
            identifier = key(job_id, data['id'])
            stored = db.get(MailSummary, identifier)
            group = group_id(data)
            serialized = json.dumps(data)
            if not stored:
                stored = MailSummary(key=identifier, account_id=account.id, folder_key=job_id, message_id=data['id'], thread_id=group, data=serialized)
                db.add(stored)
                changed = True
            elif stored.data != serialized:
                groups.add(stored.thread_id)
                changed = True
            stored.thread_id, stored.data, stored.sort_at = group, serialized, sort_date(data)
            stored.scan = checkpoint.get('scan', '')
            groups.add(group)
        for identifier in batch.get('seen', []):
            db.execute(update(MailSummary).where(MailSummary.key == key(job_id, identifier)).values(scan=checkpoint.get('scan', '')))
        removed = batch.get('deleted', [])
        conditions = []
        if removed:
            conditions.append(MailSummary.message_id.in_(removed))
        if batch.get('sweep'):
            conditions.append(MailSummary.scan != checkpoint['scan'])
        if conditions:
            for stale in db.scalars(select(MailSummary).where(MailSummary.folder_key == job_id, or_(*conditions))).all():
                groups.add(stale.thread_id)
                db.delete(stale)
                changed = True
        db.flush()
        rebuild_threads(db, job_id, groups)
        if changed:
            row.version += 1
        row.checkpoint = json.dumps(checkpoint)
        if batch['done']:
            row.complete = True
            row.synced_at = time.time()
        for label in batch.get('labels', []):
            ensure_folder(db, account.id, label['id'], label)
        job.status = 'completed' if batch['done'] else 'queued'
        job.available_at = time.time() + (.1 if not batch['done'] else 0)
        job.owner, job.lease_until, job.attempts, job.error = '', 0, 0, ''
        job.priority = 0
    if changed:
        cache.incr(f'mail-version:{account.id}')
    return True


def overlay_labels(aid, result):
    values = result if isinstance(result, list) else [result]
    if not values or not isinstance(values[0], dict) or 'threadId' not in values[0]:
        return result
    with Session() as db:
        rows = db.scalars(select(MailSummary).where(MailSummary.account_id == aid, MailSummary.message_id.in_([value['id'] for value in values]))).all()
        labels = {}
        for row in rows:
            labels.setdefault(row.message_id, set()).update(json.loads(row.data).get('labels', []))
        for value in values:
            if value['id'] in labels:
                value['labels'] = sorted(labels[value['id']])
    return result


def update_after_write(aid, ids, thread_ids, add, remove, action, imap=False):
    with Session.begin() as db:
        account = db.scalar(select(Account).where(Account.id == aid).with_for_update())
        if not account:
            return
        account.write_revision += 1
        conditions = [MailSummary.message_id.in_(ids)]
        if thread_ids:
            conditions.append(MailSummary.thread_id.in_([group_id({'threadId': tid}) for tid in thread_ids]))
        rows = db.scalars(select(MailSummary).where(MailSummary.account_id == aid, or_(*conditions))).all()
        affected = {}
        for row in rows:
            data = json.loads(row.data)
            values = set(data.get('labels', [])) - set(remove)
            values.update(add)
            if action == 'trash':
                values.discard('INBOX'); values.add('TRASH')
            elif action == 'untrash':
                values.discard('TRASH'); values.add('INBOX')
            data['labels'] = sorted(values)
            folder = db.get(MailFolder, row.folder_key)
            affected.setdefault(row.folder_key, set()).add(row.thread_id)
            # IMAP destination UIDs must be obtained from the server, never invented.
            gone = folder.folder not in values and folder.folder not in ('ALL',)
            if gone:
                db.delete(row)
            else:
                row.data = json.dumps(data)
            if not imap:
                for label in values - {'UNREAD'}:
                    target = db.get(MailFolder, key(aid, label))
                    if target and target.key != row.folder_key:
                        clone_key = key(target.key, row.message_id)
                        clone = db.get(MailSummary, clone_key)
                        if not clone:
                            db.add(MailSummary(key=clone_key, account_id=aid, folder_key=target.key, message_id=row.message_id, thread_id=row.thread_id, sort_at=row.sort_at, data=json.dumps(data)))
                        else:
                            clone.data = json.dumps(data)
                        affected.setdefault(target.key, set()).add(row.thread_id)
        db.flush()
        for folder_key, groups in affected.items():
            rebuild_threads(db, folder_key, groups)
            db.get(MailFolder, folder_key).version += 1


def fence_write(aid):
    with Session.begin() as db:
        db.execute(update(Account).where(Account.id == aid).values(write_revision=Account.write_revision + 1))


def clear_account(db, aid):
    folders = select(MailFolder.key).where(MailFolder.account_id == aid)
    db.execute(delete(MailThread).where(MailThread.folder_key.in_(folders)))
    db.execute(delete(MailSummary).where(MailSummary.account_id == aid))
    db.execute(delete(SyncJob).where(SyncJob.account_id == aid))
    db.execute(delete(MailFolder).where(MailFolder.account_id == aid))
