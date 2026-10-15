"""Bounded anonymous access. Stores counters and reservations, never question/answer text."""
from __future__ import annotations
import base64, hashlib, hmac, json, sqlite3, threading, time, uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

class Limited(Exception):
    pass

@dataclass(frozen=True)
class Limits:
    registrations_per_ip_day: int = 30
    registrations_per_hour: int = 100
    answers_per_ip_minute: int = 12
    answers_per_install_hour: int = 30
    answers_per_install_day: int = 120
    answers_per_day: int = 500
    tokens_per_day: int = 300000

class Policy:
    def __init__(self, database: str, secret: str, limits: Limits = Limits(), clock=time.time):
        if len(secret) < 32:
            raise ValueError('SESSION_SECRET must have at least 32 characters')
        self.secret=secret.encode(); self.limits=limits; self.clock=clock
        self.lock=threading.Lock()
        if database != ':memory:': Path(database).parent.mkdir(parents=True,exist_ok=True)
        self.db=sqlite3.connect(database,check_same_thread=False,isolation_level=None,timeout=10)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS counters(scope TEXT,subject TEXT,bucket TEXT,value INTEGER NOT NULL,PRIMARY KEY(scope,subject,bucket));
          CREATE TABLE IF NOT EXISTS daily(day TEXT PRIMARY KEY,requests INTEGER NOT NULL,tokens INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS reservations(id TEXT PRIMARY KEY,day TEXT,reserved INTEGER,settled INTEGER NOT NULL DEFAULT 0);
        ''')
    def day(self):
        return datetime.fromtimestamp(self.clock(),ZoneInfo('Asia/Shanghai')).date().isoformat()
    def subject(self, value: str):
        return hmac.new(self.secret,value.encode(),hashlib.sha256).hexdigest()[:32]
    def _consume(self, scope, subject, bucket, maximum):
        row=self.db.execute('SELECT value FROM counters WHERE scope=? AND subject=? AND bucket=?',(scope,subject,str(bucket))).fetchone()
        if row and row[0]>=maximum: raise Limited('请求较多，请稍后再试')
        self.db.execute('INSERT INTO counters VALUES(?,?,?,1) ON CONFLICT(scope,subject,bucket) DO UPDATE SET value=value+1',(scope,subject,str(bucket)))
    def register(self, install_id: str, ip: str):
        install_id=str(uuid.UUID(install_id)); now=int(self.clock()); day=self.day()
        with self.lock:
            self.db.execute('BEGIN IMMEDIATE')
            try:
                self._consume('register_ip_day',self.subject(ip),day,self.limits.registrations_per_ip_day)
                self._consume('register_global_hour','global',now//3600,self.limits.registrations_per_hour)
                self.db.execute('COMMIT')
            except BaseException:
                self.db.execute('ROLLBACK');raise
        payload=json.dumps(dict(id=install_id,exp=now+7*86400),separators=(',',':')).encode()
        encoded=base64.urlsafe_b64encode(payload).rstrip(b'=')
        signature=base64.urlsafe_b64encode(hmac.new(self.secret,encoded,hashlib.sha256).digest()).rstrip(b'=')
        return (encoded+b'.'+signature).decode(),now+7*86400
    def identify(self, token: str):
        try:
            if len(token)>512: raise ValueError()
            payload,signature=token.encode().split(b'.',1)
            expected=base64.urlsafe_b64encode(hmac.new(self.secret,payload,hashlib.sha256).digest()).rstrip(b'=')
            if not hmac.compare_digest(expected,signature): raise ValueError()
            decoded=json.loads(base64.urlsafe_b64decode(payload+b'='*((-len(payload))%4)))
            if not int(self.clock())<int(decoded['exp'])<=int(self.clock())+8*86400: raise ValueError()
            return str(uuid.UUID(decoded['id']))
        except (ValueError,KeyError,TypeError,json.JSONDecodeError):
            raise ValueError('访问凭据已过期，请重试') from None
    def reserve(self, install: str, ip: str, tokens: int):
        day=self.day();now=int(self.clock());reservation=uuid.uuid4().hex
        with self.lock:
            self.db.execute('BEGIN IMMEDIATE')
            try:
                row=self.db.execute('SELECT requests,tokens FROM daily WHERE day=?',(day,)).fetchone() or (0,0)
                if row[0]>=self.limits.answers_per_day or row[1]+tokens>self.limits.tokens_per_day:
                    raise Limited('今日共享服务用量已达上限，请明天再试')
                self._consume('answer_ip_minute',self.subject(ip),now//60,self.limits.answers_per_ip_minute)
                self._consume('answer_install_hour',self.subject(install),now//3600,self.limits.answers_per_install_hour)
                self._consume('answer_install_day',self.subject(install),day,self.limits.answers_per_install_day)
                self.db.execute('INSERT INTO daily VALUES(?,1,?) ON CONFLICT(day) DO UPDATE SET requests=requests+1,tokens=tokens+excluded.tokens',(day,tokens))
                self.db.execute('INSERT INTO reservations(id,day,reserved) VALUES(?,?,?)',(reservation,day,tokens))
                # Only counter metadata is retained. Keep at most two weeks.
                cutoff=datetime.fromtimestamp(self.clock()-14*86400,ZoneInfo('Asia/Shanghai')).date().isoformat()
                self.db.execute('DELETE FROM reservations WHERE day<?',(cutoff,))
                self.db.execute('DELETE FROM daily WHERE day<?',(cutoff,))
                self.db.execute("DELETE FROM counters WHERE (scope LIKE '%day' AND bucket<?) OR (scope LIKE '%minute' AND CAST(bucket AS INTEGER)<?) OR (scope LIKE '%hour' AND CAST(bucket AS INTEGER)<?)",(cutoff,now//60-1440,now//3600-48))
                self.db.execute('COMMIT')
            except BaseException:
                self.db.execute('ROLLBACK');raise
        return reservation
    def settle(self, reservation: str, actual_tokens: int | None):
        # Unknown usage remains fully reserved, including after process/network failures.
        with self.lock:
            self.db.execute('BEGIN IMMEDIATE')
            try:
                row=self.db.execute('SELECT day,reserved,settled FROM reservations WHERE id=?',(reservation,)).fetchone()
                if row and not row[2]:
                    if actual_tokens is not None:
                        actual=max(0,int(actual_tokens))
                        self.db.execute('UPDATE daily SET tokens=MAX(0,tokens+?) WHERE day=?',(actual-row[1],row[0]))
                    self.db.execute('UPDATE reservations SET settled=1 WHERE id=?',(reservation,))
                self.db.execute('COMMIT')
            except BaseException:
                self.db.execute('ROLLBACK');raise
