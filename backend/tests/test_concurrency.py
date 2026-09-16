import json
import multiprocessing
import time
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import patch

from sqlalchemy import select, delete, update

from app.core import Session, Account, User, MailFolder, MailSummary, MailThread, SyncJob, ComposeOperation, cache, encrypt, engine
from app.migrate import migrate
from app import indexing
from app.concurrency import admission, slots, imap_client, discard_account
from app.mail import MailError
from app.worker import claim


def competing_reader(aid, queue):
    with admission(aid, 'read'):
        queue.put(time.monotonic())
        time.sleep(.35)


class IndexTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        migrate()
        migrate()

    def setUp(self):
        with Session.begin() as db:
            db.execute(delete(User))
            user = User(id=str(uuid.uuid4()), email='test@example.test', password_hash='test')
            db.add(user); db.flush()
            account = Account(id=str(uuid.uuid4()), user_id=user.id, email='mail@example.test', provider='imap', secret=encrypt({'password': 'test'}))
            db.add(account); db.flush()
            self.aid, self.user = account.id, SimpleNamespace(id=user.id)
        cache.flushdb()

    def batch(self, items, done=True, **extra):
        return {'items': items, 'state': {'scan': 'cycle'}, 'done': done, **extra}

    def item(self, number, labels=None):
        return {'id': f'm{number}', 'threadId': f't{number}', 'subject': str(number), 'date': 'Mon, 01 Jan 2024 00:00:00 +0000', 'labels': labels or ['INBOX', 'UNREAD'], 'internalDate': str(number * 1000)}

    def seed(self, items):
        job = indexing.enqueue(self.aid)
        identifier, owner = claim()
        with Session() as db:
            account = db.get(Account, self.aid)
        self.assertTrue(indexing.commit_batch(identifier, owner, account, self.batch(items)))
        return identifier

    def test_pagination_version_and_metadata_only(self):
        items = [dict(self.item(n), text='secret body', html='secret html', attachments=['secret']) for n in range(65)]
        self.seed(items)
        first = indexing.list_threads(self.aid, 'INBOX')
        self.assertEqual(len(first['items']), 30)
        second = indexing.list_threads(self.aid, 'INBOX', first['nextCursor'])
        self.assertFalse({x['id'] for x in first['items']} & {x['id'] for x in second['items']})
        self.assertNotIn('text', first['items'][0])
        indexing.update_after_write(self.aid, ['m64'], [], [], ['UNREAD'], 'labels')
        with self.assertRaises(MailError) as error:
            indexing.list_threads(self.aid, 'INBOX', first['nextCursor'])
        self.assertEqual(error.exception.code, 'cursor_expired')

    def test_write_fences_older_sync(self):
        identifier = self.seed([self.item(1)])
        indexing.enqueue(self.aid)
        identifier, owner = claim()
        with Session() as db:
            old = db.get(Account, self.aid)
        indexing.update_after_write(self.aid, ['m1'], [], [], ['UNREAD'], 'labels')
        self.assertFalse(indexing.commit_batch(identifier, owner, old, self.batch([self.item(1)])))
        self.assertNotIn('UNREAD', indexing.list_threads(self.aid, 'INBOX')['items'][0]['labels'])

    def test_worker_reclaim_and_stale_owner(self):
        identifier = indexing.enqueue(self.aid)['jobId']
        _, owner = claim()
        with Session.begin() as db:
            db.get(SyncJob, identifier).lease_until = time.time() - 1
        _, successor = claim()
        self.assertNotEqual(owner, successor)
        with Session() as db:
            account = db.get(Account, self.aid)
        self.assertFalse(indexing.commit_batch(identifier, owner, account, self.batch([self.item(1)])))
        self.assertTrue(indexing.commit_batch(identifier, successor, account, self.batch([self.item(2)])))

    def test_reset_and_deletion(self):
        self.seed([self.item(1), self.item(2)])
        indexing.enqueue(self.aid)
        identifier, owner = claim()
        with Session() as db:
            account = db.get(Account, self.aid)
        indexing.commit_batch(identifier, owner, account, self.batch([self.item(3)], reset=True))
        self.assertEqual([x['id'] for x in indexing.list_threads(self.aid, 'INBOX')['items']], ['m3'])

    def test_merge_duplicate_jobs(self):
        with ThreadPoolExecutor(max_workers=10) as pool:
            identifiers = list(pool.map(lambda _: indexing.enqueue(self.aid)['jobId'], range(20)))
        self.assertEqual(len(set(identifiers)), 1)
        self.assertIsNotNone(claim())
        self.assertIsNone(claim())

    def test_reconnected_account_rejects_old_batch(self):
        indexing.enqueue(self.aid)
        identifier, owner = claim()
        with Session() as db:
            old = db.get(Account, self.aid)
        with Session.begin() as db:
            db.get(Account, self.aid).credential_version += 1
        self.assertFalse(indexing.commit_batch(identifier, owner, old, self.batch([self.item(1)])))

    def test_independent_lanes_overlap(self):
        def operation(lane):
            with admission(self.aid, lane):
                time.sleep(2)
        start = time.monotonic()
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(operation, ['read', 'read', 'write', 'sync']))
        self.assertLess(time.monotonic() - start, 3.0)

    def test_cross_process_read_limit(self):
        context = multiprocessing.get_context('spawn')
        queue = context.Queue()
        processes = [context.Process(target=competing_reader, args=(self.aid, queue)) for _ in range(3)]
        with admission(self.aid, 'read'):
            for process in processes:
                process.start()
            entered = [queue.get(timeout=15) for _ in processes]
        for process in processes:
            process.join(10)
            self.assertEqual(process.exitcode, 0)
        entered.sort()
        self.assertGreater(entered[-1] - entered[0], .55)

    def test_write_queue_timeout_is_rate_limit(self):
        with slots([(self.aid + ':write', 1)]):
            with self.assertRaises(MailError) as error:
                with slots([(self.aid + ':write', 1)], wait=.1):
                    pass
            self.assertEqual(error.exception.status, 429)

    def test_imap_pool_exclusive_and_reused(self):
        created = []
        class Client:
            def __init__(self): self.imap = self; self.closed = False; created.append(self)
            def noop(self): return 'OK', []
            def _ok(self, result): return result
            def close(self): self.closed = True
        account = SimpleNamespace(id=self.aid, credential_version=1)
        with imap_client(account, Client, 'read') as first:
            with imap_client(account, Client, 'read') as second:
                self.assertIsNot(first, second)
        with imap_client(account, Client, 'read') as third:
            self.assertIn(third, created)
        self.assertEqual(len(created), 2)
        discard_account(self.aid)
        self.assertTrue(all(c.closed for c in created))

    def test_send_pending_survives_redis_loss(self):
        from app.main import send, Compose
        key = indexing.key(self.aid, 'compose-test')
        with Session.begin() as db:
            db.add(ComposeOperation(key=key, account_id=self.aid, send_status='pending'))
        cache.flushdb()
        @contextmanager
        def provider(*args):
            yield SimpleNamespace(), SimpleNamespace(email='mail@example.test')
        with patch('app.main.provider_for', provider), patch('app.main.limited'):
            with self.assertRaises(MailError) as error:
                send(self.aid, Compose(composeId='compose-test', to='recipient@example.test'), None, self.user)
        self.assertEqual(error.exception.code, 'send_uncertain')


if __name__ == '__main__':
    unittest.main()
