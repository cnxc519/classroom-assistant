"""Classroom-only fixed-prompt gateway. Provider credentials never reach a client."""
from __future__ import annotations
import asyncio, contextlib, json, os, uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass
import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, ConfigDict
from policy import Limits, Limited, Policy

PROMPT=('你是课堂学习助手。用户给出最近的课堂语音转写，可能存在同音字和识别错误。'
        '找出最后一个完整提问，结合片段上下文作答。先用一行写出你理解的问题，再给出两三句简短口头回答，需要时补充解释。'
        '可以结合语境判断明显的同音字，但不能臆造缺失的题目、数值或关键条件。没有明确问题或条件不足时直接说明。'
        r'使用简洁 Markdown。数学表达式使用标准 LaTeX：行内用 \( 和 \)，独立公式用 \[ 和 \]。不要输出 HTML。')

@dataclass(frozen=True)
class Settings:
    api_key: str
    secret: str
    database: str='/var/lib/classroom-assistant/usage.sqlite3'
    upstream: str='https://api.deepseek.com/chat/completions'
    model: str='deepseek-flash'
    max_output: int=1000
    concurrency: int=3
    enabled: bool=True
    limits: Limits=Limits()
    @classmethod
    def from_env(cls):
        return cls(api_key=os.environ.get('DEEPSEEK_API_KEY',''),secret=os.environ.get('SESSION_SECRET',''),
                   database=os.environ.get('STATE_DB','/var/lib/classroom-assistant/usage.sqlite3'),
                   enabled=os.environ.get('API_ENABLED','1')=='1',
                   limits=Limits(answers_per_day=int(os.environ.get('DAILY_REQUEST_LIMIT','5000')),
                                 tokens_per_day=int(os.environ.get('DAILY_TOKEN_LIMIT','3000000')),
                                 answers_per_install_hour=int(os.environ.get('INSTALL_HOURLY_REQUEST_LIMIT','200')),
                                 answers_per_install_day=int(os.environ.get('INSTALL_DAILY_REQUEST_LIMIT','1000')),
                                 answers_per_ip_minute=int(os.environ.get('IP_MINUTE_REQUEST_LIMIT','30'))))

class SessionBody(BaseModel):
    model_config=ConfigDict(extra='forbid')
    install_id: uuid.UUID
class AnswerBody(BaseModel):
    model_config=ConfigDict(extra='forbid')
    transcript: str=Field(min_length=1,max_length=8000)

def create_app(settings: Settings, transport=None):
    if not settings.api_key: raise ValueError('DEEPSEEK_API_KEY must be configured on the server')
    policy=Policy(settings.database,settings.secret,settings.limits)
    semaphore=asyncio.Semaphore(settings.concurrency)
    @asynccontextmanager
    async def lifespan(app):
        app.state.client=httpx.AsyncClient(transport=transport,timeout=httpx.Timeout(45,connect=10),follow_redirects=False,trust_env=False)
        yield
        await app.state.client.aclose()
        policy.db.close()
    app=FastAPI(title='Classroom Assistant',docs_url=None,redoc_url=None,openapi_url=None,lifespan=lifespan)
    app.state.policy=policy
    def client_ip(request):
        host=request.client.host if request.client else 'unknown'
        # Only the loopback reverse proxy may supply this header; Caddy overwrites it.
        if host in ('127.0.0.1','::1'):
            return request.headers.get('x-classroom-client-ip',host)[:100]
        return host
    @app.middleware('http')
    async def bounds(request,call_next):
        content_length=request.headers.get('content-length','0')
        try: too_large=int(content_length)>32768
        except ValueError: return JSONResponse({'detail':'请求格式错误'},status_code=400)
        if too_large: return JSONResponse({'detail':'课堂片段过长'},status_code=413)
        response=await call_next(request)
        response.headers['Cache-Control']='no-store'
        response.headers['X-Content-Type-Options']='nosniff'
        return response
    @app.get('/health')
    async def health(): return dict(status='ok',version='0.3.1')
    @app.post('/v1/session')
    async def session(body:SessionBody,request:Request):
        if not settings.enabled: raise HTTPException(503,'共享服务暂时暂停')
        try: token,expires=policy.register(str(body.install_id),client_ip(request))
        except Limited as error: raise HTTPException(429,str(error)) from None
        return dict(token=token,expires_at=expires)
    @app.post('/v1/answer')
    async def answer(body:AnswerBody,request:Request):
        if not settings.enabled: raise HTTPException(503,'共享服务暂时暂停')
        authorization=request.headers.get('authorization','')
        try:
            if not authorization.startswith('Bearer '): raise ValueError()
            install=policy.identify(authorization[7:])
        except ValueError: raise HTTPException(401,'访问凭据已过期，请重试') from None
        text=body.transcript.strip()
        if not text: raise HTTPException(400,'还没有识别到问题文字')
        try: await asyncio.wait_for(semaphore.acquire(),timeout=0.1)
        except TimeoutError: raise HTTPException(429,'当前使用较多，请稍后重试') from None
        reservation=None;upstream=None
        try:
            # UTF-8 bytes plus output/framing conservatively bound token use before dispatch.
            estimate=len((PROMPT+text+'最近的课堂片段：').encode())+settings.max_output+512
            reservation=policy.reserve(install,client_ip(request),estimate)
            payload=dict(model=settings.model,messages=[dict(role='system',content=PROMPT),dict(role='user',content='最近的课堂片段：\n'+text)],
                         stream=True,max_tokens=settings.max_output,thinking=dict(type='disabled'),stream_options=dict(include_usage=True))
            call=request.app.state.client.build_request('POST',settings.upstream,headers={'Authorization':'Bearer '+settings.api_key},json=payload)
            upstream=await request.app.state.client.send(call,stream=True)
            if upstream.status_code!=200:
                policy.settle(reservation,0)
                code=429 if upstream.status_code==429 else 503
                raise HTTPException(code,'模型服务暂时不可用，请稍后重试')
        except Limited as error:
            semaphore.release();raise HTTPException(429,str(error)) from None
        except HTTPException:
            if upstream is not None: await upstream.aclose()
            semaphore.release();raise
        except (httpx.HTTPError,TimeoutError):
            if upstream is not None: await upstream.aclose()
            if reservation: policy.settle(reservation,None)
            semaphore.release();raise HTTPException(503,'连接模型服务失败，请稍后重试') from None
        except asyncio.CancelledError:
            if upstream is not None: await upstream.aclose()
            if reservation: policy.settle(reservation,None)
            semaphore.release();raise
        except Exception:
            if upstream is not None: await upstream.aclose()
            if reservation: policy.settle(reservation,None)
            semaphore.release();raise HTTPException(503,'共享服务暂时不可用，请稍后重试') from None
        def event(payload): return 'data: '+json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n\n'
        async def stream():
            actual=None;complete=False
            try:
                async with asyncio.timeout(90):
                    async for line in upstream.aiter_lines():
                        if await request.is_disconnected(): break
                        if not line.startswith('data:'): continue
                        raw=line[5:].strip()
                        if raw=='[DONE]': complete=True;break
                        if not raw: continue
                        item=json.loads(raw)
                        usage=item.get('usage') or {}
                        if isinstance(usage.get('total_tokens'),int): actual=usage['total_tokens']
                        if item.get('error'): raise ValueError('upstream stream error')
                        for choice in item.get('choices',[]):
                            delta=choice.get('delta') or {}
                            content=delta.get('content')
                            if content: yield event({'choices':[{'delta':{'content':str(content)},'finish_reason':None}]})
                            finish=choice.get('finish_reason')
                            if finish:
                                complete=True
                                yield event({'choices':[{'delta':{},'finish_reason':finish}]})
                if complete: yield 'data: [DONE]\n\n'
                else: yield event({'error':{'message':'连接中断，回答可能不完整，请重试'}})
            except (httpx.HTTPError,TimeoutError,ValueError,json.JSONDecodeError):
                yield event({'error':{'message':'连接中断，回答可能不完整，请重试'}})
            finally:
                await upstream.aclose()
                policy.settle(reservation,actual)
                semaphore.release()
        return StreamingResponse(stream(),media_type='text/event-stream',headers={'Cache-Control':'no-store','X-Accel-Buffering':'no'})
    return app

# Construct only when launched as a service. Tests call create_app with isolated configuration.
if os.environ.get('DEEPSEEK_API_KEY'):
    app=create_app(Settings.from_env())
