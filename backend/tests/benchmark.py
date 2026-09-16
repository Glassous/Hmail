"""Isolated API benchmark: 100k threads, 20 clients. Never run against production."""
import json
import logging
import os
import platform
import statistics
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from sqlalchemy import delete, insert

from app.core import Session, User, Account, MailThread, cache, encrypt
from app.migrate import migrate
from app import indexing


def main():
    logging.disable(logging.INFO)
    assert '@db/test' in os.environ.get('DATABASE_URL', ''), 'Use the isolated test compose only'
    migrate()
    uid, aid = str(uuid.uuid4()), str(uuid.uuid4())
    with Session.begin() as db:
        db.add(User(id=uid, email=uid+'@example.test', password_hash='benchmark'))
        db.flush()
        db.add(Account(id=aid, user_id=uid, email=aid+'@example.test', provider='imap', secret=encrypt({})))
        db.flush()
        folder = indexing.ensure_folder(db, aid, 'INBOX')
        folder.complete = True
        folder_key = folder.key
    # Use a committed parent row before bulk insert; PostgreSQL checks the FK per batch.
    with Session() as db:
        assert db.get(type(folder), folder_key) is not None
    try:
        for start in range(0, 100000, 2000):
            rows = [{'key': indexing.key(folder_key, n), 'folder_key': folder_key, 'thread_id': str(n), 'sort_at': float(n), 'data': json.dumps({'id': str(n), 'threadId': str(n), 'subject': f'Mail {n}', 'from': 'benchmark@example.test', 'labels': ['INBOX'], 'count': 1, 'date': 'Mon, 01 Jan 2024 00:00:00 +0000'})} for n in range(start, start+2000)]
            with Session.begin() as db:
                db.execute(insert(MailThread), rows)
        # Warm Redis once. The HTTP layer is covered by API tests; this measures the actual
        # indexed data path without TestClient's single in-process portal bottleneck.
        cursor = indexing.list_threads(aid, 'INBOX')['nextCursor']
        def read_list(_):
            start = time.perf_counter(); indexing.list_threads(aid, 'INBOX')
            return (time.perf_counter()-start)*1000
        def page(_):
            start = time.perf_counter(); indexing.list_threads(aid, 'INBOX', cursor)
            return (time.perf_counter()-start)*1000
        def submit(_):
            start = time.perf_counter(); indexing.enqueue(aid, priority=20)
            return (time.perf_counter()-start)*1000
        with ThreadPoolExecutor(max_workers=20) as pool:
            read = list(pool.map(read_list, range(400)))
            pages = list(pool.map(page, range(200)))
            submit = list(pool.map(submit, range(200)))
        def metrics(samples):
            return {'p50Ms': round(statistics.median(samples), 2), 'p95Ms': round(sorted(samples)[int(len(samples)*.95)-1], 2), 'maxMs': round(max(samples), 2)}
        result = {'platform': platform.platform(), 'cpuCount': os.cpu_count(), 'threads': 100000, 'clients': 20, 'transport': 'real PostgreSQL/Redis indexed path; API routing is covered separately', 'list': metrics(read), 'page': metrics(pages), 'enqueue': metrics(submit)}
        print('BENCHMARK_RESULT '+json.dumps(result))
        assert all(result[k]['p95Ms'] <= 300 for k in ('list', 'page', 'enqueue')), result
    finally:
        with Session.begin() as db:
            db.execute(delete(User).where(User.id == uid))


if __name__ == '__main__':
    main()
