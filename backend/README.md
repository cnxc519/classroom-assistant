# 自托管问答服务

Python 3.12+。后端只接受课堂文字，模型固定 `deepseek-flash`，thinking 显式关闭，输出最多 1000 token。客户端不能覆盖系统提示词、上游或模型参数。

```sh
python3 -m venv /opt/classroom-assistant/venv
/opt/classroom-assistant/venv/bin/pip install -r requirements.txt
python -m unittest discover -s . -p test_gateway.py -v
```

复制 `.env.example` 到仓库外 `/etc/classroom-assistant.env`，填入供应商密钥与高熵随机 `SESSION_SECRET`，权限 root:root / 0600。不要提交环境文件、数据库或签名文件。将 `server.py`、`policy.py` 放到 `/opt/classroom-assistant/current`，安装并启动附带的 systemd 单元。

后端监听 `127.0.0.1:8779`，以 systemd DynamicUser 运行；状态文件在 `/var/lib/classroom-assistant`。仅由本机反向代理访问。Caddy 示例放入自己的 HTTPS 域名块：

```caddyfile
handle_path /classroom-api/* {
    request_body {
        max_size 32KB
    }
    reverse_proxy 127.0.0.1:8779 {
        header_up X-Classroom-Client-IP {remote_host}
        flush_interval -1
    }
}
```

代理必须覆盖客户端 IP 头、限制实际请求体大小，并使用 HTTPS。若前面有 CDN，应配置可信代理以取得正确客户端 IP。修改 Android `AppSettings.API` 为自己的 HTTPS 地址。

接口：`GET /health`、`POST /v1/session`（随机 UUID `install_id`）、`POST /v1/answer`（Bearer 会话，JSON `transcript`，返回 SSE）。禁止额外请求字段。没有文档或静态文件端点。

默认限额：每 IP 每天注册 30 次、全局每小时注册 100 次；每 IP 每分钟回答 12 次，每安装每小时 30 / 每天 120 次；全局每天最多 500 次、保守 token 预算 300,000，并发 3。每日额度按北京时间重置。环境文件可调全局日请求/token 上限；其他配置见 `policy.py`。`API_ENABLED=0` 暂停服务，修改后重启本服务生效。

发起请求前按输入 UTF-8 字节数、输出上限及额外余量预留预算，收到 usage 后按实际用量结算；无法确认用量时保留预留值，因此可能提前用尽额度。这是用量上限，不是账单金额保证。匿名安装标识可以重建，公开服务仍可能遭滥用，按需要接入真实账户或邀请码。

不在日志中记录题目、回答或 Authorization。保留 systemd 启停诊断，关闭 Uvicorn access log。数据库仅含匿名额度元数据；备份和监控同样应避免采集私有环境文件。
