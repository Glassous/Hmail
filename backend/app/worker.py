"""Durable, bounded sync scheduler; run separately with python -m app.worker."""
import json
import logging
import random
import signal
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

from sqlalchemy import select, update, or_, and_

from .core import Session, Account, MailFolder, MailSummary, SyncJob, cache
from .indexing import key, enqueue, commit_batch
from .sync_providers import fetch_batch

log = logging.getLogger('hmail')
STOP = threading.Event()


def claim():
    now = time.time()
    with Session.begin() as db:
        job = db.scalar(select(SyncJob).where(or_(and_(SyncJob.status.in_(['queued', 'retry']), SyncJob.available_at <= now), and_(SyncJob.status == 'running', SyncJob.lease_until < now))).order_by(SyncJob.priority.desc(), SyncJob.available_at).with_for_update(skip_locked=True).limit(1))
        if not job:
            return None
        job.status, job.owner, job.lease_until = 'running', uuid.uuid4().hex, now + 90
        return job.id, job.owner


def run_batch(identifier, owner):
    from .main import provider_for
    stopped = threading.Event()
    def heartbeat():
        while not stopped.wait(20):
            with Session.begin() as db:
                db.execute(update(SyncJob).where(SyncJob.id == identifier, SyncJob.owner == owner).values(lease_until=time.time() + 90))
    heart = threading.Thread(target=heartbeat, daemon=True)
    heart.start()
    try:
        with Session() as db:
            job = db.get(SyncJob, identifier)
            if not job or job.owner != owner:
                return
            account = db.get(Account, job.account_id)
            if not account:
                return
            folder = db.get(MailFolder, identifier)
            checkpoint = json.loads(folder.checkpoint)
            # Only load the current UID window's metadata, not the whole mailbox.
            known = {}
            if account.provider == 'imap':
                before = checkpoint.get('before', 0) if not checkpoint.get('finished') else 0
                # Message identity order is not UID order; the batch performs a DB lookup below.
                class MetadataLookup(dict):
                    def __contains__(self, mid):
                        with Session() as lookup:
                            stored = lookup.get(MailSummary, key(identifier, mid))
                        if stored:
                            self[mid] = json.loads(stored.data)
                        return stored is not None
                    def all_ids(self):
                        with Session() as lookup:
                            return lookup.scalars(select(MailSummary.message_id).where(MailSummary.folder_key == identifier)).all()
                known = MetadataLookup()
        with provider_for(SimpleNamespace(id=account.user_id), account.id, 'sync') as (provider, snapshot):
            batch = fetch_batch(provider, job.folder, checkpoint, known)
            if account.provider == 'imap':
                provider.folders = provider._folders()
                provider.system, provider.fallback = provider._system_folders()
            batch['labels'] = provider.labels()
        commit_batch(identifier, owner, snapshot, batch)
    except Exception as exc:
        code = getattr(exc, 'code', 'sync_failed')
        with Session.begin() as db:
            job = db.get(SyncJob, identifier)
            if job and job.owner == owner:
                job.attempts += 1
                job.error = code
                job.status = 'failed' if code in ('reconnect', 'authorization', 'not_found', 'configuration') else 'retry'
                job.available_at = time.time() + min(300, 2 ** min(job.attempts, 8)) + random.random() * 3
                job.owner, job.lease_until = '', 0
        log.warning('sync_failed code=%s', code)
    finally:
        stopped.set()
        heart.join(timeout=2)


def schedule():
    with Session() as db:
        accounts = db.scalars(select(Account).where(Account.status == 'connected')).all()
        for account in accounts:
            folders = db.scalars(select(MailFolder).where(MailFolder.account_id == account.id)).all()
            if not folders:
                enqueue(account.id, 'INBOX', 20)
                continue
            for folder in folders:
                job = db.get(SyncJob, folder.key)
                interval = 30 if account.accessed_at > time.time() - 300 else 300
                if not job or (job.status == 'completed' and folder.synced_at < time.time() - interval):
                    enqueue(account.id, folder.folder, 10 if folder.folder in ('INBOX', 'DRAFT') else 0)


def main():
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    pending = set()
    last_schedule = last_cleanup = 0
    with ThreadPoolExecutor(max_workers=4) as executor:
        while not STOP.is_set():
            cache.setex('mail-worker-heartbeat', 30, str(time.time()))
            if time.time() - last_schedule > 10:
                schedule()
                last_schedule = time.time()
            if time.time() - last_cleanup > 300:
                from .main import cleanup_uploads
                cleanup_uploads()
                last_cleanup = time.time()
            pending = {future for future in pending if not future.done()}
            while len(pending) < 4:
                job = claim()
                if not job:
                    break
                pending.add(executor.submit(run_batch, *job))
            STOP.wait(.5)


if __name__ == '__main__':
    main()
