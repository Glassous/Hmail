"""Cross-process admission control and exclusively leased IMAP clients."""
import atexit
import logging
import os
import threading
import time
import uuid
from contextlib import contextmanager

from .core import cache
from .mail import MailError

log = logging.getLogger('hmail')
TOTAL = int(os.getenv('MAIL_REMOTE_LIMIT', '16'))
BACKGROUND = int(os.getenv('MAIL_BACKGROUND_LIMIT', '4'))
READS = int(os.getenv('MAIL_ACCOUNT_READ_LIMIT', '2'))
LEASE = 90
WAIT = float(os.getenv('MAIL_QUEUE_WAIT', '10'))
IDLE = float(os.getenv('MAIL_POOL_IDLE', '60'))

ACQUIRE = """
local now=tonumber(ARGV[1]); local deadline=tonumber(ARGV[2]); local owner=ARGV[3]
for i,key in ipairs(KEYS) do
 redis.call('ZREMRANGEBYSCORE',key,'-inf',now)
 if redis.call('ZCARD',key)>=tonumber(ARGV[i+3]) then return 0 end
end
for i,key in ipairs(KEYS) do redis.call('ZADD',key,deadline,owner); redis.call('EXPIRE',key,180) end
return 1
"""
RENEW = """
for i,key in ipairs(KEYS) do if not redis.call('ZSCORE',key,ARGV[1]) then return 0 end end
for i,key in ipairs(KEYS) do redis.call('ZADD',key,ARGV[2],ARGV[1]); redis.call('EXPIRE',key,180) end
return 1
"""


@contextmanager
def slots(limits, wait=WAIT):
    owner = uuid.uuid4().hex
    keys = ['mail-capacity:' + key for key, _ in limits]
    started = time.monotonic()
    while not cache.eval(ACQUIRE, len(keys), *keys, time.time(), time.time() + LEASE, owner, *[n for _, n in limits]):
        if time.monotonic() - started >= wait:
            raise MailError('请求队列已满，请稍后重试', 'rate_limit', 429)
        time.sleep(.05)
    stopped, lost = threading.Event(), threading.Event()
    def renew():
        while not stopped.wait(20):
            try:
                if not cache.eval(RENEW, len(keys), *keys, owner, time.time() + LEASE):
                    lost.set()
                    return
            except Exception:
                lost.set()
                return
    thread = threading.Thread(target=renew, daemon=True)
    thread.start()
    try:
        yield lost
    finally:
        stopped.set()
        thread.join(timeout=6)
        try:
            for key in keys:
                cache.zrem(key, owner)
        except Exception:
            log.warning('mail_capacity_release_failed')
        log.info('mail_queue_wait_ms=%s', round((time.monotonic() - started) * 1000))


@contextmanager
def admission(aid, lane):
    limits = [('global', TOTAL), (f'{aid}:{lane}', READS if lane == 'read' else 1)]
    if lane == 'sync':
        limits.append(('background', BACKGROUND))
    with slots(limits) as lost:
        yield lost


_pool = {}
_guard = threading.Lock()
_shutdown = threading.Event()


def close(client):
    """Closing a pooled connection must never turn a finished operation into a failure."""
    try:
        client.close()
    except Exception:
        log.warning('imap_close_failed')


def discard_account(aid):
    with _guard:
        clients = [entry[0] for key in list(_pool) if key[0] == aid for entry in _pool.pop(key)]
    for client in clients:
        close(client)


def reap():
    expired = []
    with _guard:
        for key in list(_pool):
            alive = []
            for client, returned in _pool[key]:
                if time.monotonic() - returned >= IDLE:
                    expired.append(client)
                else:
                    alive.append((client, returned))
            if alive:
                _pool[key] = alive
            else:
                del _pool[key]
    for client in expired:
        close(client)


def _reaper():
    while not _shutdown.wait(10):
        reap()


threading.Thread(target=_reaper, daemon=True).start()
atexit.register(_shutdown.set)


@contextmanager
def imap_client(account, factory, lane):
    key = (account.id, account.credential_version, lane)
    client = None
    with _guard:
        if _pool.get(key):
            client, _ = _pool[key].pop()
    if client:
        try:
            client._ok(client.imap.noop())
            log.info('imap_connection_reused=1')
        except Exception:
            close(client)
            client = None
    if client is None:
        client = factory()
    client.readonly = lane != 'write'
    try:
        yield client
    except BaseException:
        close(client)
        raise
    else:
        with _guard:
            entries = _pool.setdefault(key, [])
            if len(entries) < (READS if lane == 'read' else 1):
                entries.append((client, time.monotonic()))
                client = None
        if client:
            close(client)
