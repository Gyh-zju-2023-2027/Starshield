"""B 站直播间 WebSocket 实时弹幕客户端。

协议参考 B 站直播开放平台常见实现（auth op=7 / heartbeat op=2 / message op=5）。
"""

from __future__ import annotations

import json
import logging
import struct
import threading
import time
from typing import Callable, Dict, Iterator, List, Optional, Tuple

import brotli
import requests
import websocket

LOGGER = logging.getLogger("bilichat_ingest.live_ws")

HEADER_SIZE = 16
OP_HEARTBEAT = 2
OP_HEARTBEAT_REPLY = 3
OP_MESSAGE = 5
OP_AUTH = 7
OP_AUTH_REPLY = 8

ROOM_INIT_URL = "https://api.live.bilibili.com/room/v1/Room/room_init"
DANMU_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
DEFAULT_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)


def _pack(body: bytes, operation: int) -> bytes:
    pack_len = len(body) + HEADER_SIZE
    header = struct.pack(">IHHII", pack_len, HEADER_SIZE, 1, operation, 1)
    return header + body


def _iter_frames(data: bytes) -> Iterator[Tuple[int, bytes]]:
    """从二进制流切分完整帧，yield (operation, body)。"""
    offset = 0
    while offset + HEADER_SIZE <= len(data):
        pack_len, header_len, proto_ver, operation, _seq = struct.unpack_from(">IHHII", data, offset)
        if pack_len < HEADER_SIZE or offset + pack_len > len(data):
            break
        body = data[offset + header_len : offset + pack_len]
        if proto_ver == 2:
            try:
                body = brotli.decompress(body)
            except brotli.error:
                LOGGER.debug("[LiveWS] brotli 解压失败，跳过该包")
                offset += pack_len
                continue
            yield from _iter_frames(body)
        elif proto_ver in (0, 1):
            yield operation, body
        offset += pack_len


def _parse_danmaku(payload: Dict) -> Optional[Dict]:
    """解析 DANMU_MSG 为统一 record 结构。"""
    if payload.get("cmd") != "DANMU_MSG":
        return None
    info = payload.get("info")
    if not isinstance(info, list) or len(info) < 3:
        return None
    text = info[1]
    if not isinstance(text, str) or not text.strip():
        return None
    user_meta = info[2] if isinstance(info[2], list) else []
    uid = user_meta[0] if len(user_meta) > 0 else 0
    nickname = user_meta[1] if len(user_meta) > 1 else ""
    ctime = None
    if isinstance(info[0], list) and len(info[0]) > 4 and isinstance(info[0][4], (int, float)):
        ts = info[0][4]
        ctime = int(ts / 1000) if ts > 1_000_000_000_000 else int(ts)
    dm_id = payload.get("dmid") or payload.get("msgid")
    return {
        "uid": uid,
        "nickname": nickname,
        "text": text.strip(),
        "ctime": ctime,
        "rpid": str(dm_id) if dm_id is not None else f"live_{uid}_{ctime or int(time.time())}",
    }


class BiliLiveDanmakuClient:
    """单房间实时弹幕监听。"""

    def __init__(
        self,
        room_id: int,
        *,
        cookie: Optional[str] = None,
        on_danmaku: Optional[Callable[[Dict], None]] = None,
        on_error: Optional[Callable[[str], None]] = None,
    ) -> None:
        self.input_room_id = room_id
        self.real_room_id: Optional[int] = None
        self.token: Optional[str] = None
        self.host: Optional[str] = None
        self.port: Optional[int] = None
        self.cookie = cookie
        self.on_danmaku = on_danmaku
        self.on_error = on_error
        self._ws: Optional[websocket.WebSocketApp] = None
        self._running = False
        self._authenticated = False
        self._heartbeat_thread: Optional[threading.Thread] = None
        self._sess = requests.Session()
        self._sess.headers.update(
            {
                "User-Agent": DEFAULT_UA,
                "Referer": f"https://live.bilibili.com/{room_id}",
            }
        )
        if cookie:
            self._sess.headers["Cookie"] = cookie.strip()

    def _resolve_room(self) -> None:
        resp = self._sess.get(ROOM_INIT_URL, params={"id": self.input_room_id}, timeout=10)
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            raise RuntimeError(f"room_init code={body.get('code')} msg={body.get('message')}")
        self.real_room_id = body["data"]["room_id"]

        resp = self._sess.get(DANMU_INFO_URL, params={"id": self.real_room_id}, timeout=10)
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            raise RuntimeError(f"getDanmuInfo code={body.get('code')} msg={body.get('message')}")
        data = body.get("data") or {}
        self.token = data.get("token")
        hosts = data.get("host_list") or []
        if not hosts:
            raise RuntimeError("getDanmuInfo 未返回 host_list")
        host_info = hosts[0]
        self.host = host_info.get("host")
        self.port = host_info.get("wss_port") or host_info.get("ws_port")
        if not self.host or not self.port:
            raise RuntimeError(f"host 信息不完整: {host_info}")

    def _send_auth(self, ws: websocket.WebSocketApp) -> None:
        assert self.real_room_id is not None
        auth = {
            "uid": 0,
            "roomid": self.real_room_id,
            "protover": 2,
            "platform": "web",
            "type": 2,
            "key": self.token or "",
        }
        ws.send(_pack(json.dumps(auth).encode("utf-8"), OP_AUTH), opcode=websocket.ABNF.OPCODE_BINARY)

    def _send_heartbeat(self, ws: websocket.WebSocketApp) -> None:
        ws.send(_pack(b"", OP_HEARTBEAT), opcode=websocket.ABNF.OPCODE_BINARY)

    def _heartbeat_loop(self, ws: websocket.WebSocketApp) -> None:
        while self._running:
            time.sleep(30)
            if not self._running:
                break
            try:
                self._send_heartbeat(ws)
            except Exception as exc:  # noqa: BLE001
                LOGGER.warning("[LiveWS] 心跳发送失败: %s", exc)
                break

    def _handle_frame(self, operation: int, body: bytes) -> None:
        if operation == OP_AUTH_REPLY:
            try:
                payload = json.loads(body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                LOGGER.warning("[LiveWS] 认证响应解析失败")
                return
            if payload.get("code") == 0:
                self._authenticated = True
                LOGGER.info("[LiveWS] room=%s 认证成功", self.real_room_id)
            else:
                msg = f"认证失败 code={payload.get('code')}"
                LOGGER.error("[LiveWS] room=%s %s", self.real_room_id, msg)
                if self.on_error:
                    self.on_error(msg)
            return

        if operation != OP_MESSAGE:
            return

        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return

        if not isinstance(payload, dict) or payload.get("cmd") != "DANMU_MSG":
            return
        record = _parse_danmaku(payload)
        if record and self.on_danmaku:
            record["room_id"] = self.real_room_id
            self.on_danmaku(record)

    def _handle_message(self, ws: websocket.WebSocketApp, message: bytes) -> None:
        if isinstance(message, str):
            return
        for operation, body in _iter_frames(message):
            self._handle_frame(operation, body)

    def _on_open(self, ws: websocket.WebSocketApp) -> None:
        LOGGER.info("[LiveWS] WebSocket 已连接 room=%s", self.real_room_id)
        self._send_auth(ws)
        self._heartbeat_thread = threading.Thread(target=self._heartbeat_loop, args=(ws,), daemon=True)
        self._heartbeat_thread.start()

    def _on_error(self, ws: websocket.WebSocketApp, error: Exception) -> None:
        LOGGER.warning("[LiveWS] WebSocket 错误 room=%s: %s", self.real_room_id, error)
        if self.on_error:
            self.on_error(str(error))

    def _on_close(self, ws: websocket.WebSocketApp, status_code: int, msg: str) -> None:
        LOGGER.info("[LiveWS] WebSocket 关闭 room=%s code=%s msg=%s", self.real_room_id, status_code, msg)

    def start(self) -> None:
        self._resolve_room()
        assert self.host and self.port
        url = f"wss://{self.host}:{self.port}/sub"
        self._running = True
        self._ws = websocket.WebSocketApp(
            url,
            on_open=self._on_open,
            on_message=self._handle_message,
            on_error=self._on_error,
            on_close=self._on_close,
            header=[f"User-Agent: {DEFAULT_UA}"],
        )
        LOGGER.info("[LiveWS] 开始监听 room=%s → %s", self.real_room_id, url)
        self._ws.run_forever(ping_interval=0)

    def stop(self) -> None:
        self._running = False
        if self._ws:
            self._ws.close()
