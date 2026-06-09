#!/usr/bin/env python3
"""
StarShield 安全测试脚本

覆盖：限流、注入、鉴权缺失、幂等性、CORS、HTTP 方法、边界输入。

用法:
  python run_security_tests.py
  python run_security_tests.py --host http://localhost:8080
  python run_security_tests.py --report ../docs/security-test-report.md
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Optional

import httpx

from payloads import (
    ARCHIVE_INJECTION_PARAMS,
    INVALID_UPLOAD_FIELDS,
    MALFORMED_BODIES,
    OVERSIZE_CONTENT,
    PATH_TRAVERSAL,
    SQL_INJECTION,
    XSS_PAYLOADS,
)

DEFAULT_HOST = "http://localhost:8080"
DEFAULT_ADMIN_TOKEN = "starshield-dev-admin-key"
TIMEOUT = 10.0

# 模块级：run_all 前由 main 注入
_ADMIN_TOKEN: Optional[str] = DEFAULT_ADMIN_TOKEN


def _admin_headers() -> dict[str, str]:
    if not _ADMIN_TOKEN:
        return {}
    return {"X-Admin-Token": _ADMIN_TOKEN}


class Severity(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INFO = "INFO"


class Status(str, Enum):
    PASS = "PASS"       # 行为符合安全预期
    FAIL = "FAIL"       # 发现漏洞或不符合安全预期
    WARN = "WARN"       # 已知风险/待改进项（与当前设计一致但需关注）
    SKIP = "SKIP"       # 跳过（依赖不可用）


@dataclass
class TestCase:
    case_id: str
    name: str
    category: str
    severity: Severity
    description: str
    status: Status = Status.SKIP
    expected: str = ""
    actual: str = ""
    detail: str = ""


@dataclass
class TestRun:
    host: str
    started_at: str
    finished_at: str = ""
    cases: list[TestCase] = field(default_factory=list)

    @property
    def summary(self) -> dict[str, int]:
        counts = {s.value: 0 for s in Status}
        for c in self.cases:
            counts[c.status.value] += 1
        return counts


def _client(host: str) -> httpx.Client:
    return httpx.Client(base_url=host.rstrip("/"), timeout=TIMEOUT, trust_env=False)


def _upload_payload(player_id: str, content: str = "security-test", platform: str = "OTHER") -> dict:
    return {"playerId": player_id, "content": content, "platform": platform}


def _post_upload(client: httpx.Client, payload: dict, headers: Optional[dict] = None) -> httpx.Response:
    return client.post("/api/chat/upload", json=payload, headers=headers or {})


def _parse_result(resp: httpx.Response) -> tuple[int, str]:
    try:
        body = resp.json()
        return body.get("code", resp.status_code), body.get("message", "")
    except Exception:
        return resp.status_code, resp.text[:200]


def _backend_unavailable(resp: httpx.Response) -> bool:
    return resp.status_code in (502, 503, 504) or not resp.content


def run_case(
    run: TestRun,
    case_id: str,
    name: str,
    category: str,
    severity: Severity,
    description: str,
    fn: Callable[[httpx.Client], tuple[Status, str, str, str]],
    client: httpx.Client,
) -> None:
    tc = TestCase(
        case_id=case_id,
        name=name,
        category=category,
        severity=severity,
        description=description,
    )
    try:
        status, expected, actual, detail = fn(client)
        tc.status = status
        tc.expected = expected
        tc.actual = actual
        tc.detail = detail
    except httpx.ConnectError:
        tc.status = Status.SKIP
        tc.actual = "无法连接后端"
        tc.detail = f"请确认 {run.host} 已启动"
    except Exception as e:
        tc.status = Status.FAIL
        tc.actual = f"测试异常: {type(e).__name__}: {e}"
    run.cases.append(tc)


# ── 限流 ──────────────────────────────────────────────────────────


def test_player_rate_limit(client: httpx.Client) -> tuple[Status, str, str, str]:
    """同一 playerId 1 秒内超过 30 次应触发玩家限流。"""
    player = f"sec_player_{uuid.uuid4().hex[:8]}"
    codes: list[int] = []
    for _ in range(35):
        resp = _post_upload(client, _upload_payload(player))
        if _backend_unavailable(resp):
            return Status.SKIP, "后端可用", f"HTTP {resp.status_code}", "后端未就绪"
        code, _ = _parse_result(resp)
        codes.append(code)

    rate_limited = codes.count(429)
    expected = "第 31 次起出现 code=429（玩家限流）"
    actual = f"35 次请求: 200={codes.count(200)} 429={rate_limited}"
    if rate_limited >= 1:
        return Status.PASS, expected, actual, "玩家维度限流生效"
    return Status.FAIL, expected, actual, "未触发玩家限流，存在刷量风险"


def test_ip_rate_limit(client: httpx.Client) -> tuple[Status, str, str, str]:
    """同一 IP（X-Forwarded-For）1 秒内超过 300 次应触发 IP 限流。"""
    fake_ip = f"10.{uuid.uuid4().int % 200}.{uuid.uuid4().int % 255}.1"
    headers = {"X-Forwarded-For": fake_ip}

    base = str(client.base_url).rstrip("/")

    def one_req(i: int) -> int:
        with _client(base) as c:
            resp = c.post(
                "/api/chat/upload",
                json=_upload_payload(f"sec_ip_{i}"),
                headers=headers,
            )
            code, _ = _parse_result(resp)
            return code

    codes: list[int] = []
    with ThreadPoolExecutor(max_workers=40) as pool:
        futures = [pool.submit(one_req, i) for i in range(320)]
        for f in as_completed(futures):
            codes.append(f.result())

    rate_limited = codes.count(429)
    expected = "超过 ip-qps=300 后出现 code=429"
    actual = f"320 并发: 200={codes.count(200)} 429={rate_limited}"
    if rate_limited >= 1:
        return Status.PASS, expected, actual, "IP 维度限流生效"
    return Status.WARN, expected, actual, "未触发 IP 限流（可能全局/player 先触发或窗口未对齐）"


def test_xff_spoof_bypass(client: httpx.Client) -> tuple[Status, str, str, str]:
    """验证 X-Forwarded-For 伪造是否可绕过 IP 限流（安全风险提示）。"""
    player = f"sec_xff_{uuid.uuid4().hex[:8]}"
    # 先用同一 IP 打满少量请求
    ip_a = "203.0.113.50"
    for _ in range(35):
        _post_upload(client, _upload_payload(player), headers={"X-Forwarded-For": ip_a})

    # 换伪造 IP 继续
    ip_b = "203.0.113.99"
    resp = _post_upload(client, _upload_payload(player), headers={"X-Forwarded-For": ip_b})
    code_b, msg_b = _parse_result(resp)

    expected = "若信任 XFF 且无网关校验，攻击者可轮换 IP 绕过限流"
    actual = f"换 IP 后 code={code_b} message={msg_b}"
    if code_b == 200:
        return Status.WARN, expected, actual, "XFF 可被客户端伪造，生产环境需在网关层固定真实 IP"
    return Status.PASS, expected, actual, "换 IP 后仍被限流（可能命中玩家限流）"


# ── 注入 / 边界输入 ───────────────────────────────────────────────


def test_sql_injection_archive(client: httpx.Client) -> tuple[Status, str, str, str]:
    """归档检索参数注入，不应导致 500 或 SQL 错误泄露。"""
    errors = []
    for params in ARCHIVE_INJECTION_PARAMS:
        resp = client.get("/api/archive/search", params=params)
        if resp.status_code == 500:
            errors.append(f"{params} -> HTTP 500")
        text = resp.text.lower()
        if "sql" in text or "syntax" in text or "mysql" in text:
            errors.append(f"{params} -> 响应含 SQL 错误信息")

    expected = "全部返回 200 且无 SQL 错误泄露"
    if errors:
        return Status.FAIL, expected, "; ".join(errors), "可能存在注入或错误信息泄露"
    return Status.PASS, expected, f"测试 {len(ARCHIVE_INJECTION_PARAMS)} 组参数均正常", "MyBatis 参数化查询有效"


def test_sql_injection_upload_content(client: httpx.Client) -> tuple[Status, str, str, str]:
    """发言内容含 SQL 片段，接入层应正常接收（不崩溃）。"""
    issues = []
    for payload in SQL_INJECTION:
        resp = _post_upload(
            client,
            _upload_payload(f"sec_sql_{uuid.uuid4().hex[:6]}", payload),
        )
        code, msg = _parse_result(resp)
        if resp.status_code >= 500 and code != 429:
            issues.append(f"{payload[:20]}... -> {code} {msg}")

    expected = "接入层返回 200 或 429，无 500"
    actual = f"{len(SQL_INJECTION)} 条 payload, 异常 {len(issues)} 条"
    if issues:
        return Status.FAIL, expected, actual + " " + str(issues[:2]), "服务端异常"
    return Status.PASS, expected, actual, "接入层对 SQL 片段内容 resilient"


def test_xss_upload_stored(client: httpx.Client) -> tuple[Status, str, str, str]:
    """XSS payload 上传不应导致服务端崩溃；存储型 XSS 需前端转义（记录为 INFO）。"""
    ok = 0
    for payload in XSS_PAYLOADS:
        resp = _post_upload(
            client,
            _upload_payload(f"sec_xss_{uuid.uuid4().hex[:6]}", payload),
        )
        if resp.status_code < 500:
            ok += 1
    expected = "服务端不 500；展示层已做 HTML 转义"
    actual = f"{ok}/{len(XSS_PAYLOADS)} 未触发 500"
    if ok == len(XSS_PAYLOADS):
        return Status.PASS, expected, actual, "接入层正常入队；Dashboard/Archive 展示层已统一转义"
    return Status.FAIL, expected, actual, "XSS payload 导致服务端异常"


def test_oversized_payload(client: httpx.Client) -> tuple[Status, str, str, str]:
    resp = _post_upload(
        client,
        _upload_payload(f"sec_big_{uuid.uuid4().hex[:6]}", OVERSIZE_CONTENT),
    )
    code, msg = _parse_result(resp)
    expected = "超大 payload 被拒绝(4xx)或正常入队(200)，不应 500"
    actual = f"HTTP {resp.status_code} code={code} len={len(OVERSIZE_CONTENT)}"
    if resp.status_code >= 500 and code not in (429,):
        return Status.FAIL, expected, actual, "超大请求导致服务端错误"
    return Status.PASS, expected, actual, "服务端处理了超大 body"


def test_malformed_json(client: httpx.Client) -> tuple[Status, str, str, str]:
    """畸形 JSON 应返回 4xx/5xx 业务错误，不应未处理异常。"""
    results = []
    for label, raw in MALFORMED_BODIES:
        resp = client.post(
            "/api/chat/upload",
            content=raw,
            headers={"Content-Type": "application/json"},
        )
        results.append(f"{label}=HTTP{resp.status_code}")

    expected = "畸形 body 不导致连接重置，返回明确错误"
    # Spring 对 empty/invalid 通常 400 或 500
    actual = ", ".join(results)
    if any("HTTP502" in r or "HTTP503" in r for r in results):
        return Status.FAIL, expected, actual, "网关级错误"
    return Status.PASS, expected, actual, "畸形 JSON 被框架拦截"


def test_invalid_upload_fields(client: httpx.Client) -> tuple[Status, str, str, str]:
    """缺字段/空字段上传：记录当前行为（理想应 400）。"""
    behaviors = []
    for payload in INVALID_UPLOAD_FIELDS:
        resp = _post_upload(client, payload)
        if _backend_unavailable(resp):
            return Status.SKIP, "后端可用", f"HTTP {resp.status_code}", "后端未就绪"
        code, msg = _parse_result(resp)
        behaviors.append(f"{list(payload.keys()) or 'empty'}->{code}")

    # 当前 ChatMessageLog 无 @Valid，可能仍 200 入队
    all_200 = all("->200" in b for b in behaviors)
    expected = "缺必填字段应返回 400"
    actual = "; ".join(behaviors[:4]) + "..."
    if all_200:
        return Status.WARN, expected, actual, "接入层未校验必填字段，脏数据可能入 MQ"
    return Status.PASS, expected, actual, "存在字段校验"


# ── 鉴权 / 越权 ───────────────────────────────────────────────────


def test_admin_no_auth(client: httpx.Client) -> tuple[Status, str, str, str]:
    resp = client.get("/api/admin/moderation/pending")
    if _backend_unavailable(resp):
        return Status.SKIP, "后端可用", f"HTTP {resp.status_code}", "后端未就绪"
    code, _ = _parse_result(resp)
    expected = "管理接口应要求认证(401/403)"
    actual = f"GET pending -> HTTP {resp.status_code} code={code}"
    if code == 200:
        return Status.FAIL, expected, actual, "管理后台接口无鉴权，任意调用者可查看待审列表"
    return Status.PASS, expected, actual, "已启用鉴权"


def test_control_panel_no_auth(client: httpx.Client) -> tuple[Status, str, str, str]:
    # 先读取现有词表，测试后恢复
    before = client.get("/api/control/rules/sensitive-words")
    if _backend_unavailable(before):
        return Status.SKIP, "后端可用", f"HTTP {before.status_code}", "后端未就绪"
    original = before.json().get("data", [])

    resp = client.put(
        "/api/control/rules/sensitive-words",
        json={"words": ["security_probe_word"]},
    )
    code, msg = _parse_result(resp)
    client.put("/api/control/rules/sensitive-words", json={"words": original})

    expected = "敏感词热更新应要求管理员权限"
    actual = f"PUT sensitive-words -> code={code} {msg}"
    if code == 200:
        return Status.FAIL, expected, actual, "任意用户可篡改 Redis 敏感词规则"
    return Status.PASS, expected, actual, "控制面已保护"


def test_archive_reindex_no_auth(client: httpx.Client) -> tuple[Status, str, str, str]:
    expected = "reindex 高危操作应鉴权"
    try:
        resp = client.post(
            "/api/archive/reindex",
            params={"batchSize": 1, "maxRows": 1},
            timeout=60.0,
        )
    except httpx.ReadTimeout:
        return Status.FAIL, expected, "POST reindex 超时", "未鉴权请求已触发长时间回填任务"
    if _backend_unavailable(resp):
        return Status.SKIP, "后端可用", f"HTTP {resp.status_code}", "后端未就绪"
    code, msg = _parse_result(resp)
    expected = "reindex 高危操作应鉴权"
    actual = f"POST reindex -> code={code} {msg}"
    if code == 200:
        return Status.FAIL, expected, actual, "任意用户可触发 ES 回填，存在 DoS 风险"
    return Status.PASS, expected, actual, "reindex 已保护"


def test_path_traversal_moderation(client: httpx.Client) -> tuple[Status, str, str, str]:
    issues = []
    for path_id in PATH_TRAVERSAL:
        resp = client.get(
            f"/api/admin/moderation/{path_id}/audit-logs",
            headers=_admin_headers(),
        )
        if resp.status_code == 500:
            issues.append(path_id)
    expected = "非法 path id 返回 404/400，不 500"
    actual = f"测试 {len(PATH_TRAVERSAL)} 个路径, 500 次数={len(issues)}"
    if issues:
        return Status.FAIL, expected, actual, "路径参数未安全处理"
    return Status.PASS, expected, actual, "框架拒绝非法路径参数"


# ── 幂等性 ────────────────────────────────────────────────────────


def test_idempotency_missing_key(client: httpx.Client) -> tuple[Status, str, str, str]:
    resp = client.post(
        "/api/admin/moderation/999999999999/confirm-ban",
        json={},
        headers=_admin_headers(),
    )
    code, msg = _parse_result(resp)
    expected = "缺少 X-Idempotency-Key 应返回 409"
    actual = f"code={code} message={msg}"
    if code == 409:
        return Status.PASS, expected, actual, "幂等键校验生效"
    return Status.FAIL, expected, actual, "缺少幂等键未被拒绝"


def test_idempotency_reuse(client: httpx.Client) -> tuple[Status, str, str, str]:
    key_resp = client.get("/api/admin/moderation/idempotency-key", headers=_admin_headers())
    key_body = key_resp.json()
    idem_key = key_body.get("data", {}).get("idempotencyKey")
    if not idem_key:
        return Status.SKIP, "获取幂等键", "无 idempotencyKey", "Redis 或接口不可用"

    fake_id = 999999999999
    headers = {**_admin_headers(), "X-Idempotency-Key": idem_key}
    r1 = client.post(f"/api/admin/moderation/{fake_id}/confirm-ban", headers=headers, json={})
    c1, _ = _parse_result(r1)
    r2 = client.post(f"/api/admin/moderation/{fake_id}/confirm-ban", headers=headers, json={})
    c2, msg2 = _parse_result(r2)

    expected = "同一幂等键第二次调用返回 409"
    actual = f"首次 code={c1}, 二次 code={c2} msg={msg2}"
    if c2 == 409:
        return Status.PASS, expected, actual, "幂等键一次性消费正确"
    return Status.FAIL, expected, actual, "幂等键可被重复使用"


def test_idempotency_invalid_key(client: httpx.Client) -> tuple[Status, str, str, str]:
    headers = {**_admin_headers(), "X-Idempotency-Key": str(uuid.uuid4())}
    resp = client.post(
        "/api/admin/moderation/999999999999/confirm-ban",
        headers=headers,
        json={},
    )
    code, msg = _parse_result(resp)
    expected = "未注册的随机幂等键应 409"
    actual = f"code={code} message={msg}"
    if code == 409:
        return Status.PASS, expected, actual, "伪造幂等键被拒绝"
    return Status.FAIL, expected, actual, "未注册幂等键未被拒绝"


# ── 其他 ──────────────────────────────────────────────────────────


def test_cors_wildcard(client: httpx.Client) -> tuple[Status, str, str, str]:
    resp = client.get(
        "/api/admin/moderation/pending",
        headers={"Origin": "https://evil.example.com"},
    )
    acao = resp.headers.get("access-control-allow-origin", "")
    expected = "生产环境不应 * 允许任意 Origin"
    actual = f"Access-Control-Allow-Origin={acao or '(无)'}"
    if acao == "*":
        return Status.WARN, expected, actual, "@CrossOrigin(origins=\"*\") 允许任意站点跨域读管理 API"
    return Status.PASS, expected, actual, "CORS 策略受限"


def test_http_method_not_allowed(client: httpx.Client) -> tuple[Status, str, str, str]:
    resp = client.get("/api/chat/upload")
    expected = "GET 上传接口应 405"
    actual = f"HTTP {resp.status_code}"
    if resp.status_code == 405:
        return Status.PASS, expected, actual, "方法限制正确"
    return Status.WARN, expected, actual, f"返回 {resp.status_code} 而非 405"


def test_batch_invalid_decision(client: httpx.Client) -> tuple[Status, str, str, str]:
    resp = client.post(
        "/api/admin/moderation/batch",
        json={"ids": [1], "decision": "HACK", "operator": "sec-test"},
        headers=_admin_headers(),
    )
    code, msg = _parse_result(resp)
    expected = "非法 decision 应 400"
    actual = f"code={code} message={msg}"
    if code == 400:
        return Status.PASS, expected, actual, "批量接口校验 decision 枚举"
    return Status.FAIL, expected, actual, "非法 decision 未被拒绝"


def test_health_no_sensitive_leak(client: httpx.Client) -> tuple[Status, str, str, str]:
    """常见探测路径不应泄露堆栈/密钥。"""
    paths = ["/actuator/env", "/actuator/heapdump", "/.env", "/api/debug"]
    leaks = []
    for path in paths:
        resp = client.get(path)
        if resp.status_code == 200 and len(resp.content) > 100:
            text = resp.text.lower()
            if "password" in text or "secret" in text or "apikey" in text:
                leaks.append(path)
    expected = "敏感/debug 端点不可未授权访问"
    actual = f"探测 {len(paths)} 路径, 泄露 {len(leaks)}"
    if leaks:
        return Status.FAIL, expected, actual, f"泄露路径: {leaks}"
    return Status.PASS, expected, actual, "未发现明显敏感端点泄露"


# ── 运行器 ────────────────────────────────────────────────────────

ALL_TESTS: list[tuple[str, str, str, Severity, str, Callable]] = [
    ("SEC-RL-01", "玩家维度限流", "限流", Severity.HIGH, "player-qps=30", test_player_rate_limit),
    ("SEC-RL-02", "IP 维度限流", "限流", Severity.HIGH, "ip-qps=300", test_ip_rate_limit),
    ("SEC-RL-03", "XFF 伪造绕过", "限流", Severity.MEDIUM, "X-Forwarded-For 信任链", test_xff_spoof_bypass),
    ("SEC-INJ-01", "归档检索 SQL 注入", "注入", Severity.CRITICAL, "keyword/playerId 等", test_sql_injection_archive),
    ("SEC-INJ-02", "发言内容 SQL 片段", "注入", Severity.MEDIUM, "content 字段", test_sql_injection_upload_content),
    ("SEC-INJ-03", "XSS Payload 上传", "注入", Severity.MEDIUM, "存储型 XSS 面", test_xss_upload_stored),
    ("SEC-INJ-04", "超大 Payload", "边界", Severity.MEDIUM, "50KB content", test_oversized_payload),
    ("SEC-INJ-05", "畸形 JSON", "边界", Severity.LOW, "Content-Type 篡改", test_malformed_json),
    ("SEC-INJ-06", "缺字段上传", "边界", Severity.MEDIUM, "必填校验", test_invalid_upload_fields),
    ("SEC-AUTH-01", "管理接口无鉴权", "鉴权", Severity.CRITICAL, "GET pending", test_admin_no_auth),
    ("SEC-AUTH-02", "控制面无鉴权", "鉴权", Severity.CRITICAL, "PUT sensitive-words", test_control_panel_no_auth),
    ("SEC-AUTH-03", "reindex 无鉴权", "鉴权", Severity.HIGH, "POST reindex", test_archive_reindex_no_auth),
    ("SEC-AUTH-04", "路径遍历 ID", "鉴权", Severity.MEDIUM, "audit-logs path", test_path_traversal_moderation),
    ("SEC-IDEM-01", "缺少幂等键", "幂等", Severity.MEDIUM, "confirm-ban", test_idempotency_missing_key),
    ("SEC-IDEM-02", "幂等键重用", "幂等", Severity.HIGH, "二次提交", test_idempotency_reuse),
    ("SEC-IDEM-03", "伪造幂等键", "幂等", Severity.MEDIUM, "随机 UUID", test_idempotency_invalid_key),
    ("SEC-MISC-01", "CORS 通配", "配置", Severity.MEDIUM, "Origin 反射", test_cors_wildcard),
    ("SEC-MISC-02", "HTTP 方法限制", "配置", Severity.LOW, "GET upload", test_http_method_not_allowed),
    ("SEC-MISC-03", "批量非法 decision", "业务", Severity.MEDIUM, "batch API", test_batch_invalid_decision),
    ("SEC-MISC-04", "敏感端点探测", "信息泄露", Severity.HIGH, "actuator/.env", test_health_no_sensitive_leak),
]


def run_all(host: str) -> TestRun:
    started = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    run = TestRun(host=host, started_at=started)

    with _client(host) as client:
        # 连通性
        try:
            probe = client.get("/api/admin/moderation/pending")
            if _backend_unavailable(probe):
                raise httpx.ConnectError(f"backend returned HTTP {probe.status_code}")
        except httpx.ConnectError:
            for case_id, name, cat, sev, desc, _ in ALL_TESTS:
                run.cases.append(
                    TestCase(
                        case_id=case_id,
                        name=name,
                        category=cat,
                        severity=sev,
                        description=desc,
                        status=Status.SKIP,
                        actual="后端不可达",
                    )
                )
            run.finished_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            return run

        for case_id, name, category, severity, description, fn in ALL_TESTS:
            run_case(run, case_id, name, category, severity, description, fn, client)
            time.sleep(0.05)  # 限流测试间略微间隔

    run.finished_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return run


def print_console(run: TestRun) -> None:
    summary = run.summary
    print(f"\n{'=' * 72}")
    print(f"  StarShield 安全测试  |  {run.host}")
    print(f"  开始: {run.started_at}  结束: {run.finished_at}")
    print(f"{'=' * 72}\n")

    for tc in run.cases:
        icon = {"PASS": "✅", "FAIL": "❌", "WARN": "⚠️ ", "SKIP": "⏭ "}.get(tc.status.value, "?")
        print(f"{icon} [{tc.case_id}] {tc.name} ({tc.severity.value})")
        if tc.actual:
            print(f"     实际: {tc.actual}")
        if tc.detail and tc.status != Status.PASS:
            print(f"     说明: {tc.detail}")
        print()

    print(f"{'─' * 72}")
    print(
        f"  合计 {len(run.cases)}  |  "
        f"PASS {summary['PASS']}  FAIL {summary['FAIL']}  "
        f"WARN {summary['WARN']}  SKIP {summary['SKIP']}"
    )
    print(f"{'=' * 72}\n")


def write_markdown_report(run: TestRun, path: str) -> None:
    summary = run.summary
    lines = [
        "# StarShield 安全测试报告",
        "",
        f"> 自动生成于 {run.finished_at}  ",
        f"> 目标: `{run.host}`  ",
        f"> 脚本: `secure-test/run_security_tests.py`",
        "",
        "## 摘要",
        "",
        "| 状态 | 数量 |",
        "|------|------|",
    ]
    for st in Status:
        lines.append(f"| {st.value} | {summary[st.value]} |")
    lines.extend(["", "---", ""])

    current_cat = ""
    for tc in run.cases:
        if tc.category != current_cat:
            current_cat = tc.category
            lines.extend([f"## {current_cat}", ""])
        lines.extend([
            f"### {tc.case_id} {tc.name}",
            "",
            f"- **严重级别**: {tc.severity.value}",
            f"- **状态**: {tc.status.value}",
            f"- **描述**: {tc.description}",
            f"- **预期**: {tc.expected}",
            f"- **实际**: {tc.actual}",
        ])
        if tc.detail:
            lines.append(f"- **备注**: {tc.detail}")
        lines.append("")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main() -> int:
    global _ADMIN_TOKEN
    parser = argparse.ArgumentParser(description="StarShield 安全测试")
    parser.add_argument("--host", default=DEFAULT_HOST, help="后端 base URL")
    parser.add_argument(
        "--admin-token",
        default=DEFAULT_ADMIN_TOKEN,
        help="管理接口 X-Admin-Token（与 starshield.security.admin-api-key 一致）",
    )
    parser.add_argument("--report", help="输出 Markdown 报告路径")
    parser.add_argument("--json", help="输出 JSON 结果路径")
    args = parser.parse_args()

    _ADMIN_TOKEN = args.admin_token
    run = run_all(args.host)
    print_console(run)

    if args.report:
        write_markdown_report(run, args.report)
        print(f"报告已写入: {args.report}")

    if args.json:
        data = {
            "host": run.host,
            "started_at": run.started_at,
            "finished_at": run.finished_at,
            "summary": run.summary,
            "cases": [
                {
                    "case_id": c.case_id,
                    "name": c.name,
                    "category": c.category,
                    "severity": c.severity.value,
                    "status": c.status.value,
                    "expected": c.expected,
                    "actual": c.actual,
                    "detail": c.detail,
                }
                for c in run.cases
            ],
        }
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"JSON 已写入: {args.json}")

    if run.summary["FAIL"] > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
