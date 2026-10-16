import asyncio, json, os, sys, tempfile, unittest, uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch
import httpx
from fastapi.testclient import TestClient
sys.path.insert(0,str(Path(__file__).resolve().parent))
from policy import Policy,Limits,Limited
from server import Settings,create_app

SECRET='unit-test-signing-secret-not-a-real-credential-0123456789'

class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.time=[1800000000]
        self.policy=Policy(':memory:',SECRET,clock=lambda:self.time[0])
    def tearDown(self): self.policy.db.close()
    def test_token_integrity_and_expiry(self):
        identifier=str(uuid.uuid4());token,expires=self.policy.register(identifier,'test-ip')
        self.assertEqual(identifier,self.policy.identify(token))
        with self.assertRaises(ValueError): self.policy.identify(token[:-1]+'!')
        self.time[0]=expires
        with self.assertRaises(ValueError): self.policy.identify(token)
    def test_atomic_budget_and_idempotent_settlement(self):
        self.policy.limits=Limits(answers_per_day=4,tokens_per_day=1000,answers_per_ip_minute=100,answers_per_install_hour=100)
        identifier=str(uuid.uuid4())
        def call(i):
            try:return self.policy.reserve(identifier,'same-ip',200)
            except Limited:return None
        with ThreadPoolExecutor(max_workers=12) as pool: reservations=list(pool.map(call,range(12)))
        accepted=[r for r in reservations if r]
        self.assertEqual(len(accepted),4)
        self.assertEqual(self.policy.db.execute('SELECT requests,tokens FROM daily').fetchone(),(4,800))
        self.policy.settle(accepted[0],10);self.policy.settle(accepted[0],0)
        self.policy.settle(accepted[1],None)
        self.assertEqual(self.policy.db.execute('SELECT tokens FROM daily').fetchone()[0],610)
    def test_per_install_quota_and_day_rollover(self):
        self.policy.limits=Limits(answers_per_install_day=1)
        identifier=str(uuid.uuid4()); self.policy.reserve(identifier,'ip',100)
        with self.assertRaises(Limited): self.policy.reserve(identifier,'other-ip',100)
        self.time[0]+=86400
        self.policy.reserve(identifier,'ip',100)
    def test_registration_limit(self):
        self.policy.limits=Limits(registrations_per_ip_day=1)
        self.policy.register(str(uuid.uuid4()),'ip')
        with self.assertRaises(Limited): self.policy.register(str(uuid.uuid4()),'ip')

class GatewayTests(unittest.TestCase):
    def setUp(self): self.tmp=tempfile.TemporaryDirectory(dir=os.environ.get('CLASSROOM_TEST_TMP'))
    def tearDown(self): self.tmp.cleanup()
    def settings(self,**values):
        return Settings(api_key='test-provider-credential-do-not-return',secret=SECRET,database=str(Path(self.tmp.name)/'state.sqlite3'),**values)
    def token(self,client):
        response=client.post('/v1/session',json={'install_id':str(uuid.uuid4())})
        self.assertEqual(response.status_code,200)
        return {'Authorization':'Bearer '+response.json()['token']}
    def test_environment_limits_are_applied(self):
        with patch.dict(os.environ,{'DAILY_REQUEST_LIMIT':'5000','DAILY_TOKEN_LIMIT':'3000000',
                                   'INSTALL_HOURLY_REQUEST_LIMIT':'201','INSTALL_DAILY_REQUEST_LIMIT':'1001',
                                   'IP_MINUTE_REQUEST_LIMIT':'31'}):
            limits=Settings.from_env().limits
        self.assertEqual((limits.answers_per_day,limits.tokens_per_day),(5000,3000000))
        self.assertEqual((limits.answers_per_install_hour,limits.answers_per_install_day,limits.answers_per_ip_minute),(201,1001,31))
    def test_fixed_prompt_stream_math_usage_and_no_credentials(self):
        captured=[]
        def provider(request):
            data=json.loads(request.content);captured.append(data)
            self.assertEqual(data['thinking'],{'type':'disabled'})
            self.assertEqual(data['model'],'deepseek-flash')
            self.assertEqual(data['max_tokens'],1000)
            content=json.dumps({'choices':[{'delta':{'content':'答案：\\(x=\\frac{1}{2}\\)'},'finish_reason':None}]})
            usage=json.dumps({'choices':[{'delta':{},'finish_reason':'stop'}],'usage':{'total_tokens':19}})
            return httpx.Response(200,content=f'data: {content}\n\ndata: {usage}\n\ndata: [DONE]\n\n')
        app=create_app(self.settings(),httpx.MockTransport(provider))
        with TestClient(app) as client:
            headers=self.token(client)
            response=client.post('/v1/answer',headers=headers,json={'transcript':'二分之一是多少？'})
            self.assertEqual(response.status_code,200);self.assertIn('[DONE]',response.text)
            self.assertIn('frac',response.text);self.assertNotIn('test-provider',response.text)
            self.assertNotIn(SECRET,response.text)
            self.assertEqual(app.state.policy.db.execute('SELECT tokens FROM daily').fetchone()[0],19)
            self.assertEqual(response.headers['cache-control'],'no-store')
            self.assertEqual(len(captured),1)
    def test_auth_validation_limits_and_no_static_files(self):
        app=create_app(self.settings(),httpx.MockTransport(lambda r:httpx.Response(500)))
        with TestClient(app) as client:
            self.assertEqual(client.post('/v1/answer',json={'transcript':'问题'}).status_code,401)
            headers=self.token(client)
            self.assertEqual(client.post('/v1/answer',headers=headers,json={'transcript':'x'*8001}).status_code,422)
            self.assertEqual(client.post('/v1/answer',headers=headers,json={'transcript':'问题','model':'expensive-model'}).status_code,422)
            self.assertEqual(client.post('/v1/answer',content='x'*33000).status_code,413)
            for path in ['/.env','/.git/config','/docs','/openapi.json','/server.py']:
                self.assertEqual(client.get(path).status_code,404)
    def test_upstream_errors_do_not_leak_or_exhaust_concurrency(self):
        app=create_app(self.settings(concurrency=1),httpx.MockTransport(lambda r:httpx.Response(401,text='test-provider-credential-do-not-return')))
        with TestClient(app) as client:
            headers=self.token(client)
            for _ in range(3):
                result=client.post('/v1/answer',headers=headers,json={'transcript':'问题'})
                self.assertEqual(result.status_code,503)
                self.assertNotIn('test-provider',result.text)
            self.assertEqual(app.state.policy.db.execute('SELECT tokens FROM daily').fetchone()[0],0)
    def test_daily_budget_prevents_another_provider_call(self):
        calls=[]
        def provider(r): calls.append(1);return httpx.Response(200,content='data: [DONE]\n\n')
        settings=self.settings(limits=Limits(answers_per_day=1))
        app=create_app(settings,httpx.MockTransport(provider))
        with TestClient(app) as client:
            headers=self.token(client)
            self.assertEqual(client.post('/v1/answer',headers=headers,json={'transcript':'问题'}).status_code,200)
            self.assertEqual(client.post('/v1/answer',headers=headers,json={'transcript':'另一个问题'}).status_code,429)
            self.assertEqual(len(calls),1)
    def test_cancel_before_upstream_headers_releases_slot(self):
        async def exercise():
            started=asyncio.Event();calls=0
            async def provider(request):
                nonlocal calls
                calls+=1
                if calls==1: started.set();await asyncio.sleep(60)
                return httpx.Response(200,content='data: [DONE]\n\n')
            app=create_app(self.settings(concurrency=1),httpx.MockTransport(provider))
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
                    registration=await client.post('/v1/session',json={'install_id':str(uuid.uuid4())})
                    headers={'Authorization':'Bearer '+registration.json()['token']}
                    task=asyncio.create_task(client.post('/v1/answer',headers=headers,json={'transcript':'问题'}))
                    await asyncio.wait_for(started.wait(),2);task.cancel()
                    try: await task
                    except asyncio.CancelledError: pass
                    reply=await client.post('/v1/answer',headers=headers,json={'transcript':'下一个问题'})
                    self.assertEqual(reply.status_code,200)
        asyncio.run(exercise())

if __name__=='__main__': unittest.main(verbosity=2)
