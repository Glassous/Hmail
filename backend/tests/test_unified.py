"""跨账户统一视图（全部账户）的分页、排序、游标与鉴权测试。

只覆盖新增的 `/threads` 分支，不改动单账户列表的任何断言。
"""
import json
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import delete, select

from app import indexing
from app.core import Account, MailFolder, MailThread, Session, SyncJob, User, cache, encrypt
from app.mail import MailError
from app.main import app, current_user
from app.migrate import migrate

API = '/api/v1'


class UnifiedInboxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        migrate()

    def setUp(self):
        with Session.begin() as db:
            # SQLite 默认不启用外键级联，这里显式清空，保证在 sqlite 与 postgres 上行为一致。
            for table in (MailThread, MailFolder, SyncJob, Account, User):
                db.execute(delete(table))
            owner = User(id=str(uuid.uuid4()), email='unified@example.test', password_hash='test')
            db.add(owner)
            db.flush()
            self.owner = owner.id
            self.aids = []
            for address in ('alpha@example.test', 'beta@example.test', 'gamma@example.test'):
                account = Account(id=str(uuid.uuid4()), user_id=owner.id, email=address, provider='imap', secret=encrypt({'password': 'test'}))
                db.add(account)
                self.aids.append(account.id)
            stranger = User(id=str(uuid.uuid4()), email='stranger@example.test', password_hash='test')
            db.add(stranger)
            db.flush()
            self.stranger = stranger.id
            self.stranger_aid = str(uuid.uuid4())
            db.add(Account(id=self.stranger_aid, user_id=stranger.id, email='stranger@example.test', provider='imap', secret=encrypt({'password': 'test'})))
            self.loner = str(uuid.uuid4())
            db.add(User(id=self.loner, email='loner@example.test', password_hash='test'))
        cache.flushdb()
        self.client = TestClient(app)
        app.dependency_overrides[current_user] = lambda: SimpleNamespace(id=self.owner)

    def tearDown(self):
        app.dependency_overrides.pop(current_user, None)
        self.client.close()

    def as_user(self, user_id):
        app.dependency_overrides[current_user] = lambda: SimpleNamespace(id=user_id)

    def seed(self, aid, folder='INBOX', numbers=(), base=1_700_000_000.0, labels=None):
        with Session.begin() as db:
            row = indexing.ensure_folder(db, aid, folder)
            for number in numbers:
                group = f'{aid[:6]}-t{number}'
                data = {'id': f'{aid[:6]}-m{number}', 'threadId': group, 'subject': f'{folder}-{number}',
                        'from': 'sender@example.test', 'to': 'me@example.test',
                        'date': 'Mon, 01 Jan 2024 00:00:00 +0000', 'labels': list(labels or [folder])}
                db.add(MailThread(key=indexing.key(row.key, group), folder_key=row.key, thread_id=group,
                                  sort_at=base + number, data=json.dumps(data)))
            row.version += 1

    def bump(self, aid, folder='INBOX'):
        with Session.begin() as db:
            db.get(MailFolder, indexing.key(aid, folder)).version += 1

    def test_merges_accounts_in_time_order_with_account_fields(self):
        self.seed(self.aids[0], numbers=(10, 30))
        self.seed(self.aids[1], numbers=(20, 40))
        result = indexing.list_all_threads(self.aids, 'INBOX')
        self.assertEqual([item['subject'] for item in result['items']], ['INBOX-40', 'INBOX-30', 'INBOX-20', 'INBOX-10'])
        self.assertEqual([item['accountId'] for item in result['items']], [self.aids[1], self.aids[0], self.aids[1], self.aids[0]])
        self.assertEqual([item['account'] for item in result['items']], ['beta@example.test', 'alpha@example.test', 'beta@example.test', 'alpha@example.test'])

    def test_pagination_is_stable_and_deduplicated(self):
        for aid in self.aids:
            self.seed(aid, numbers=tuple(range(25)))
        seen, cursor, pages = [], '', 0
        while True:
            page = indexing.list_all_threads(self.aids, 'INBOX', cursor)
            seen.extend(item['id'] for item in page['items'])
            pages += 1
            cursor = page['nextCursor']
            if not cursor:
                break
        self.assertEqual(pages, 3)
        self.assertEqual(len(seen), 75)
        self.assertEqual(len(set(seen)), 75)

    def test_cursor_expires_when_any_account_index_changes(self):
        for aid in self.aids:
            self.seed(aid, numbers=tuple(range(15)))
        first = indexing.list_all_threads(self.aids, 'INBOX')
        self.assertTrue(first['nextCursor'])
        self.bump(self.aids[2])
        with self.assertRaises(MailError) as caught:
            indexing.list_all_threads(self.aids, 'INBOX', first['nextCursor'])
        self.assertEqual(caught.exception.code, 'cursor_expired')
        self.assertEqual(caught.exception.status, 409)

    def test_folder_dimension_and_account_isolation(self):
        self.seed(self.aids[0], folder='SENT', numbers=(5,))
        self.seed(self.aids[1], numbers=(7,))
        self.seed(self.stranger_aid, numbers=(99,))
        inbox = indexing.list_all_threads(self.aids, 'INBOX')
        self.assertEqual([item['subject'] for item in inbox['items']], ['INBOX-7'])
        self.assertNotIn('stranger@example.test', [item['account'] for item in inbox['items']])
        sent = indexing.list_all_threads(self.aids, 'SENT')
        self.assertEqual([item['subject'] for item in sent['items']], ['SENT-5'])

    def test_missing_folders_trigger_sync_jobs(self):
        result = indexing.list_all_threads(self.aids, 'TRASH')
        self.assertEqual(result['items'], [])
        with Session() as db:
            jobs = db.scalars(select(SyncJob).where(SyncJob.account_id.in_(self.aids), SyncJob.folder == 'TRASH')).all()
        self.assertEqual(len(jobs), 3)

    def test_aggregated_sync_state_reports_worst_status_and_fingerprint(self):
        for aid in self.aids:
            self.seed(aid, numbers=(1,))
        state = indexing.all_state(self.aids, 'INBOX')
        self.assertEqual(state['status'], 'queued')
        self.assertTrue(state['indexVersion'] > 0)
        self.assertIsNone(state['jobId'])
        with Session.begin() as db:
            for aid in self.aids:
                db.get(MailFolder, indexing.key(aid, 'INBOX')).complete = True
                db.add(SyncJob(id=indexing.key(aid, 'INBOX'), account_id=aid, folder='INBOX', status='completed'))
        state = indexing.all_state(self.aids, 'INBOX')
        self.assertEqual(state['status'], 'completed')
        self.assertTrue(state['historyComplete'])
        with Session.begin() as db:
            db.get(SyncJob, indexing.key(self.aids[1], 'INBOX')).status = 'running'
        self.assertEqual(indexing.all_state(self.aids, 'INBOX')['status'], 'running')

    def test_http_unified_payload(self):
        self.seed(self.aids[0], numbers=(1,))
        self.seed(self.aids[1], numbers=(2,))
        response = self.client.get(API + '/threads', params={'folder': 'INBOX'})
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual([item['subject'] for item in body['items']], ['INBOX-2', 'INBOX-1'])
        self.assertIn('accountId', body['items'][0])
        self.assertIn('account', body['items'][0])
        self.assertIn('nextCursor', body)
        self.assertIn('indexVersion', body['sync'])
        status = self.client.get(API + '/threads/sync-status', params={'folder': 'INBOX'})
        self.assertEqual(status.status_code, 200)
        self.assertIn(status.json()['status'], ('queued', 'running', 'retry', 'completed'))
        self.assertTrue(status.json()['indexVersion'] > 0)

    def test_http_cursor_expired_conflict(self):
        for aid in self.aids:
            self.seed(aid, numbers=tuple(range(15)))
        body = self.client.get(API + '/threads', params={'folder': 'INBOX'}).json()
        self.assertTrue(body['nextCursor'])
        self.bump(self.aids[0])
        response = self.client.get(API + '/threads', params={'folder': 'INBOX', 'cursor': body['nextCursor']})
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.json()['code'], 'cursor_expired')

    def test_http_invalid_cursor_rejected(self):
        self.seed(self.aids[0], numbers=(1,))
        response = self.client.get(API + '/threads', params={'folder': 'INBOX', 'cursor': 'not-a-cursor'})
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()['code'], 'validation')

    def test_http_rejects_forged_cursor_for_another_folder(self):
        for aid in self.aids:
            self.seed(aid, numbers=tuple(range(15)))
        body = self.client.get(API + '/threads', params={'folder': 'INBOX'}).json()
        response = self.client.get(API + '/threads', params={'folder': 'SENT', 'cursor': body['nextCursor']})
        self.assertEqual(response.status_code, 409)

    def test_http_unsupported_without_index(self):
        with patch.object(indexing, 'ENABLED', False):
            response = self.client.get(API + '/threads')
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()['code'], 'unsupported')
            status = self.client.get(API + '/threads/sync-status')
            self.assertEqual(status.status_code, 503)

    def test_http_without_accounts_returns_empty(self):
        self.as_user(self.loner)
        response = self.client.get(API + '/threads', params={'folder': 'INBOX'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['items'], [])

    def test_http_never_leaks_other_users_mail(self):
        self.seed(self.aids[0], numbers=(1,))
        self.seed(self.stranger_aid, numbers=(2,))
        self.as_user(self.stranger)
        response = self.client.get(API + '/threads', params={'folder': 'INBOX'})
        self.assertEqual(response.status_code, 200)
        accounts = [item['account'] for item in response.json()['items']]
        self.assertEqual(accounts, ['stranger@example.test'])


if __name__ == '__main__':
    unittest.main()
