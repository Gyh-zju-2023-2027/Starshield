"""安全测试用攻击/边界 Payload 集合。"""

SQL_INJECTION = [
    "' OR '1'='1",
    "1; DROP TABLE chat_message_log--",
    "' UNION SELECT null,null,null,null,null,null,null,null,null,null--",
    "1' AND SLEEP(3)--",
]

XSS_PAYLOADS = [
    "<script>alert('xss')</script>",
    "<img src=x onerror=alert(1)>",
    "javascript:alert(document.cookie)",
    "\"><svg/onload=alert(1)>",
]

PATH_TRAVERSAL = [
    "../../../etc/passwd",
    "..\\..\\..\\windows\\system32\\drivers\\etc\\hosts",
    "%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd",
]

NOSQL_OR_COMMAND = [
    {"$gt": ""},
    {"playerId": {"$ne": None}},
]

OVERSIZE_CONTENT = "A" * 50_000

MALFORMED_BODIES = [
    ("empty", b""),
    ("not_json", b"playerId=abc&content=hi"),
    ("array", b"[1,2,3]"),
    ("null_byte", b'{"playerId":"p\\u0000x","content":"hi","platform":"OTHER"}'),
]

INVALID_UPLOAD_FIELDS = [
    {},  # 全空
    {"playerId": "p1"},  # 缺 content/platform
    {"content": "hi", "platform": "OTHER"},  # 缺 playerId
    {"playerId": "", "content": "hi", "platform": "OTHER"},
    {"playerId": "p1", "content": "", "platform": "OTHER"},
    {"playerId": "p1", "content": "hi", "platform": "INVALID_PLATFORM_XXX"},
]

ARCHIVE_INJECTION_PARAMS = [
    {"keyword": "' OR 1=1 --"},
    {"playerId": "admin'--"},
    {"labels": "%' OR '1'='1"},
    {"decision": "BLOCK' OR '1'='1"},
    {"keyword": "test", "page": "-1"},
    {"keyword": "test", "limit": "99999"},
]
