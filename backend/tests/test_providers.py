import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.mime_parts import descriptors, parse_structure, imap_message, gmail_message
from app.providers import ImapSmtpProvider
from app.sync_providers import imap_batch, gmail_batch


class MimeTests(unittest.TestCase):
    def test_nested_structure(self):
        raw = b'(("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 5 1 NIL NIL)("APPLICATION" "PDF" ("NAME" "large.pdf") NIL NIL "BASE64" 20000000 NIL ("ATTACHMENT" ("FILENAME" "large.pdf"))) "MIXED")'
        parts = descriptors(parse_structure(raw))
        self.assertEqual([p['section'] for p in parts], ['1', '2'])
        self.assertTrue(parts[1]['attachment'])
        self.assertEqual(parts[1]['size'], 20000000)

    def test_imap_body_does_not_fetch_attachment(self):
        structure = b'1 (UID 7 BODYSTRUCTURE (("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 5 1 NIL NIL)("APPLICATION" "PDF" NIL NIL NIL "BASE64" 20000000 NIL ("ATTACHMENT" ("FILENAME" "large.pdf"))) "MIXED"))'
        requests = []
        class Provider:
            _decode = staticmethod(lambda value: ['m', 'INBOX', '9', '7'])
            _encode = staticmethod(lambda *args: 'thread')
            _select = staticmethod(lambda *args: '9')
            _ok = staticmethod(lambda response: response[1])
            _parse = staticmethod(ImapSmtpProvider._parse)
            _thread_key = staticmethod(lambda message: ('mid', 'root'))
            _labels = staticmethod(lambda *args: ['INBOX'])
            def uid(self, *args): return 'OK', [structure]
            def _fetch(self, ids, fields):
                requests.append(fields)
                raw = b'From: sender@example.test\r\nSubject: test\r\n\r\n' if b'HEADER' in fields else b'hello'
                return [('7', b'', raw)]
        provider = Provider(); provider.imap = provider
        result = imap_message(provider, 'message')
        self.assertEqual(result['text'], 'hello')
        self.assertEqual(result['attachments'][0]['id'], 'mime:2')
        self.assertFalse(any(b'PEEK[2]' in q or b'PEEK[]' in q for q in requests))

    def test_gmail_body_omits_attachment_bytes(self):
        info = {'id': 'm', 'threadId': 't', 'payload': {'headers': [], 'parts': [
            {'mimeType': 'text/plain', 'partId': '0', 'body': {'data': 'aGVsbG8'}},
            {'mimeType': 'application/pdf', 'partId': '1', 'filename': 'big.pdf', 'body': {'attachmentId': 'remote', 'size': 20000000}}]}}
        result = gmail_message(SimpleNamespace(), info)
        self.assertEqual(result['text'], 'hello')
        self.assertEqual(result['attachments'][0]['id'], 'gmail:1')


class SyncTests(unittest.TestCase):
    def test_imap_known_headers_not_refetched(self):
        calls = []
        class Provider:
            modseq = '0'
            folder_wire = lambda self, folder: 'INBOX'
            _select = lambda self, wire: '4'
            _search = lambda self, *args: [b'1', b'2']
            _encode = staticmethod(ImapSmtpProvider._encode)
            _ok = lambda self, response: response[1]
            _labels = lambda self, *args: ['INBOX']
            def uid(self, *args): return 'OK', [b'1 (UID 1 FLAGS ())', b'2 (UID 2 FLAGS ())']
            def _fetch(self, ids, fields): calls.extend(ids); return []
        provider = Provider(); provider.imap = provider
        known = {provider._encode('m', 'INBOX', '4', str(n)): {'id': provider._encode('m', 'INBOX', '4', str(n)), 'threadId': 'thread'} for n in (1, 2)}
        result = imap_batch(provider, 'INBOX', {}, known)
        self.assertEqual(calls, [])
        self.assertTrue(result['done'])
        self.assertEqual(len(result['items']), 2)

    def test_condstore_fetches_only_changed_flags(self):
        class Provider:
            modseq = '12'
            folder_wire = lambda self, folder: 'INBOX'
            _select = lambda self, wire: '4'
            _search = lambda self, *args: [b'1', b'2']
            _encode = staticmethod(ImapSmtpProvider._encode)
            _ok = lambda self, response: response[1]
            _labels = lambda self, *args: ['INBOX']
            _fetch = lambda self, *args: []
            def uid(self, *args): return 'OK', [b'2 (UID 2 FLAGS (\\Seen))']
        provider = Provider();provider.imap=provider
        class Known(dict):
            def all_ids(self): return list(self)
        known = Known({provider._encode('m', 'INBOX', '4', str(n)): {'id': provider._encode('m', 'INBOX', '4', str(n)), 'threadId': 'thread'} for n in (1,2)})
        result = imap_batch(provider,'INBOX',{'validity':'4','scan':'old','finished':True,'modseq':'10'},known)
        self.assertEqual(len(result['items']),1)
        self.assertFalse(result['sweep'])
        self.assertEqual(result['state']['modseq'],'12')

    def test_gmail_history_applies_removed_membership(self):
        class Provider:
            def history(self): return self
            def list(self, **kwargs): return kwargs
            def run(self, request): return {'historyId': '12', 'history': [{'messages': [{'id':'m'}]}]}
        provider=Provider();provider.users=provider
        with patch('app.sync_providers.gmail_metadata',return_value=[{'id':'m','threadId':'t','labels':['SENT']}]):
            result=gmail_batch(provider,'INBOX',{'historyId':'10'}, {})
        self.assertEqual(result['deleted'],['m'])
        self.assertEqual(result['state']['historyId'],'12')


if __name__ == '__main__':
    unittest.main()
