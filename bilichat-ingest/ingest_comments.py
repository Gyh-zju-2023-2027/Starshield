"""B 站评论爬虫 → 本地 JSONL 落盘 → 推送 StarShield /api/chat/upload。

工作流程：
  1) 给定一组 BV 号（命令行 ``--bvid`` 或 ``--bvid-file``），对每个 BV 拉评论；
  2) 评论先写入 ``--out-jsonl`` 本地文件，做"原始数据备份"；
  3) 再以受控速率推送到后端 ``/api/chat/upload``，由后端走
     ``MQ → Consumer → 引擎A(布隆+敏感词) + 引擎B(轻量模型/DeepSeek) → 落库``。

后端限流提示（来自 application.yml）：
  * global-qps  : 20000  → 不会成为瓶颈
  * ip-qps      : 300    → 单机爬虫只有一个出口 IP，必须保持 < 300/s
  * player-qps  : 30     → 我们用 ``BILI_<mid>`` 做 playerId，自然分散
默认 ``--rps 50`` 已经留足余量；网络抖动遇到 429 会指数退避。

@author AI (under P3 supervision)
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Tuple

import requests

CST = timezone(timedelta(hours=8))

LOGGER = logging.getLogger("bilichat_ingest")

VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
# 2025 起老接口 /x/v2/reply 单页只放 3 条置顶热评，必须走「懒加载游标」新接口
# /x/v2/reply/main：next=页号(从0开始)，mode=2(按时间)|3(按热度)，ps 上限 20
REPLY_URL = "https://api.bilibili.com/x/v2/reply/main"
DEFAULT_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)


# =============================================================
# Bilibili 抓取层
# =============================================================
def build_session(cookie: Optional[str]) -> requests.Session:
    """构造带反爬 header 的 requests Session。

    @author AI (under P3 supervision)
    """
    sess = requests.Session()
    sess.headers.update(
        {
            "User-Agent": DEFAULT_UA,
            "Accept": "application/json,text/plain,*/*",
            "Referer": "https://www.bilibili.com/",
        }
    )
    if cookie:
        sess.headers["Cookie"] = cookie.strip()
    return sess


def bvid_to_aid(sess: requests.Session, bvid: str, timeout: float = 10.0) -> int:
    """通过 ``/x/web-interface/view`` 把 BV 号换成 aid。

    @author AI (under P3 supervision)
    """
    resp = sess.get(VIEW_URL, params={"bvid": bvid}, timeout=timeout)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"view code={body.get('code')} message={body.get('message')}")
    aid = body.get("data", {}).get("aid")
    if not isinstance(aid, int):
        raise RuntimeError(f"aid 解析失败 bvid={bvid} body={body}")
    return aid


def extract_reply_text(reply: Dict) -> str:
    """提取一条评论的纯文本（剥离 emoji/at/链接富结构）。

    @author AI (under P3 supervision)
    """
    content = reply.get("content") or {}
    if isinstance(content.get("message"), str):
        return content["message"]
    msgs = content.get("msgs")
    if isinstance(msgs, list):
        return "".join(str(m) for m in msgs)
    return ""


def iter_root_replies_page(
    sess: requests.Session,
    aid: int,
    pn: int,
    ps: int,
    sort: int,
    timeout: float = 10.0,
) -> Tuple[List[Dict], Dict]:
    """拉一页根评论。返回 (replies, page_meta)。

    @author AI (under P3 supervision)
    """
    # 新接口字段：mode 替代 sort（2=时间, 3=热度），next 替代 pn（从 0 开始）
    params = {"type": 1, "oid": aid, "mode": sort, "next": pn, "ps": ps, "plat": 1}
    resp = sess.get(REPLY_URL, params=params, timeout=timeout)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"reply code={body.get('code')} next={pn} message={body.get('message')}")
    data = body.get("data") or {}
    replies = data.get("replies") or []
    cursor = data.get("cursor") or {}
    # 把 cursor 包装成原 page 字段的语义，调用方无需改
    page = {
        "count": cursor.get("all_count"),
        "size": ps,
        "is_end": cursor.get("is_end"),
        "next": cursor.get("next"),
    }
    return replies, page


def walk_root_replies(
    sess: requests.Session,
    bvid: str,
    aid: int,
    *,
    ps: int,
    sort: int,
    max_pages: int,
    sleep_sec: float,
) -> Iterator[Dict]:
    """逐页拉评论。已自动去掉重复 rpid。

    @author AI (under P3 supervision)
    """
    seen_rpid = set()
    # 新接口 next 从 0 开始，0 这一页等价于"首页热评"，1/2/3... 才是连续翻页
    for pn in range(0, max_pages):
        try:
            replies, page = iter_root_replies_page(sess, aid, pn, ps, sort)
        except Exception as exc:  # noqa: BLE001
            LOGGER.warning("[BiliFetch] bvid=%s next=%s 拉取失败: %s", bvid, pn, exc)
            time.sleep(sleep_sec * 2)
            continue

        if not replies:
            LOGGER.info("[BiliFetch] bvid=%s next=%s 空页，提前停止", bvid, pn)
            break

        new_count = 0
        for reply in replies:
            rpid = reply.get("rpid")
            if rpid is None or rpid in seen_rpid:
                continue
            seen_rpid.add(rpid)
            text = extract_reply_text(reply).strip()
            if not text:
                continue
            mid = (reply.get("member") or {}).get("mid") or "anon"
            # ctime 是 B 站评论发表时间（秒级 unix 时间戳），下游用它做"按日分布"
            ctime = reply.get("ctime")
            yield {
                "bvid": bvid,
                "aid": aid,
                "rpid": rpid,
                "mid": str(mid),
                "text": text,
                "ctime": ctime if isinstance(ctime, int) else None,
            }
            new_count += 1

        LOGGER.info(
            "[BiliFetch] bvid=%s next=%s 拉到 %s 条新评论 (累计 rpid=%s, all_count=%s)",
            bvid,
            pn,
            new_count,
            len(seen_rpid),
            page.get("count") if isinstance(page, dict) else None,
        )

        # 触底优先看 cursor.is_end；其次按 all_count 估算
        if isinstance(page, dict) and page.get("is_end"):
            LOGGER.info("[BiliFetch] bvid=%s 触底 is_end=True，停止翻页", bvid)
            break
        total = page.get("count") if isinstance(page, dict) else None
        if isinstance(total, int) and (pn + 1) * ps >= total:
            break

        time.sleep(sleep_sec)


# =============================================================
# 推送层（带本地速率限制）
# =============================================================
class TokenBucket:
    """简易令牌桶：保证 POST 速率不超过 rps，避免触发后端 IP 限流。

    @author AI (under P2 supervision)
    """

    def __init__(self, rate_per_sec: float, capacity: Optional[float] = None) -> None:
        self.rate = max(0.1, float(rate_per_sec))
        self.capacity = float(capacity if capacity is not None else self.rate)
        self.tokens = self.capacity
        self.last = time.monotonic()
        self.lock = threading.Lock()

    def acquire(self) -> None:
        while True:
            with self.lock:
                now = time.monotonic()
                self.tokens = min(self.capacity, self.tokens + (now - self.last) * self.rate)
                self.last = now
                if self.tokens >= 1:
                    self.tokens -= 1
                    return
                wait = (1 - self.tokens) / self.rate
            time.sleep(wait)


@dataclass
class PostStats:
    success: int = 0
    rate_limited: int = 0
    failed: int = 0


def fmt_create_time(ctime: Optional[int]) -> Optional[str]:
    """B 站评论的 ctime（秒级 unix 时间戳） → 上海时区 'YYYY-MM-DD HH:MM:SS' 字符串。

    必须与后端 ChatMessageLog.createTime 的 @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss",
    timezone="Asia/Shanghai") 严格对齐，否则 Jackson 反序列化会抛错而落到默认 now()。

    @author AI (under P3 supervision)
    """
    if not ctime:
        return None
    try:
        return datetime.fromtimestamp(int(ctime), tz=CST).strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError, OSError, OverflowError):
        return None


def post_chat(
    sess: requests.Session,
    base_url: str,
    payload: Dict,
    *,
    timeout: float,
) -> Tuple[bool, str]:
    """POST 一条到 ``/api/chat/upload``。返回 (success, msg)。

    被 429 / 5xx 视为可重试，由调用者处理。

    @author AI (under P2 supervision)
    """
    url = base_url.rstrip("/") + "/chat/upload"
    try:
        resp = sess.post(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            timeout=timeout,
        )
    except Exception as exc:  # noqa: BLE001
        return False, f"network: {exc}"

    if resp.status_code == 429:
        return False, "429"
    if resp.status_code >= 500:
        return False, f"http_{resp.status_code}"
    if resp.status_code >= 400:
        return False, f"http_{resp.status_code} body={resp.text[:200]}"

    try:
        body = resp.json()
    except Exception:  # noqa: BLE001
        return False, f"non_json body={resp.text[:200]}"

    code = body.get("code")
    if code == 200:
        return True, "ok"
    return False, f"code={code} message={body.get('message')}"


# =============================================================
# 主流程
# =============================================================
def load_bvids(cli_bvids: List[str], bvid_file: Optional[str]) -> List[str]:
    """合并命令行与文件中的 BV 号，保持顺序去重。

    @author AI (under P3 supervision)
    """
    out: List[str] = []
    seen = set()
    for src in (cli_bvids or []):
        if src and src not in seen:
            seen.add(src)
            out.append(src)
    if bvid_file:
        path = Path(bvid_file)
        if not path.exists():
            raise FileNotFoundError(f"--bvid-file 文件不存在: {bvid_file}")
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line not in seen:
                seen.add(line)
                out.append(line)
    if not out:
        raise SystemExit("必须通过 --bvid 或 --bvid-file 指定至少一个 BV 号")
    return out


def crawl_to_local(
    bvids: List[str],
    *,
    out_jsonl: Path,
    cookie: Optional[str],
    target_count: int,
    ps: int,
    sort: int,
    max_pages: int,
    sleep_between_pages: float,
) -> int:
    """逐 BV 抓评论 → 实时 append 到 JSONL。返回总条数。

    @author AI (under P3 supervision)
    """
    out_jsonl.parent.mkdir(parents=True, exist_ok=True)
    sess = build_session(cookie)

    total = 0
    seen_text_hash: set[int] = set()  # 跨 BV 文本去重，防止"复制粘贴"刷屏
    with out_jsonl.open("w", encoding="utf-8") as fp:
        for bvid in bvids:
            if total >= target_count:
                break
            try:
                aid = bvid_to_aid(sess, bvid)
            except Exception as exc:  # noqa: BLE001
                LOGGER.warning("[BiliFetch] bvid=%s 解析 aid 失败: %s", bvid, exc)
                continue
            LOGGER.info("[BiliFetch] 开始抓取 bvid=%s aid=%s", bvid, aid)

            for record in walk_root_replies(
                sess,
                bvid,
                aid,
                ps=ps,
                sort=sort,
                max_pages=max_pages,
                sleep_sec=sleep_between_pages,
            ):
                h = hash(record["text"])
                if h in seen_text_hash:
                    continue
                seen_text_hash.add(h)
                fp.write(json.dumps(record, ensure_ascii=False) + "\n")
                fp.flush()
                total += 1
                if total >= target_count:
                    LOGGER.info("[BiliFetch] 已达目标条数 %s，停止抓取", target_count)
                    break

    LOGGER.info("[BiliFetch] 本地落盘完成 total=%s file=%s", total, out_jsonl)
    return total


def push_to_backend(
    in_jsonl: Path,
    *,
    base_url: str,
    rps: float,
    workers: int,
    request_timeout: float,
    max_retries: int,
    platform: str,
    limit: Optional[int] = None,
) -> PostStats:
    """读取 JSONL，限速并发推到 /api/chat/upload。

    @author AI (under P2 supervision)
    """
    stats = PostStats()
    bucket = TokenBucket(rate_per_sec=rps, capacity=max(rps, 1.0))
    sess = requests.Session()

    def one(record: Dict) -> Tuple[bool, str]:
        text = (record.get("text") or "").strip()
        if not text:
            return False, "empty"
        payload = {
            "playerId": f"BILI_{record.get('mid', 'anon')}_{record.get('rpid', 'x')}",
            "content": text,
            "platform": platform,
        }
        # 把评论真实发表时间带给后端；缺失则由 MyBatis-Plus 的 strictInsertFill 退回 now()
        create_time = fmt_create_time(record.get("ctime"))
        if create_time:
            payload["createTime"] = create_time
        for attempt in range(max_retries + 1):
            bucket.acquire()
            ok, msg = post_chat(sess, base_url, payload, timeout=request_timeout)
            if ok:
                return True, msg
            if msg == "429" or msg.startswith("http_5"):
                # 退避：base 0.5s + 抖动；attempt 越大等越久
                sleep = 0.5 * (2 ** attempt) + random.random() * 0.3
                time.sleep(min(sleep, 5.0))
                continue
            return False, msg
        return False, "exhausted_retries"

    with in_jsonl.open("r", encoding="utf-8") as fp:
        records: List[Dict] = []
        for raw in fp:
            line = raw.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                continue
            if limit is not None and len(records) >= limit:
                break

    LOGGER.info("[Push] 待推送 %s 条 → %s", len(records), base_url)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(one, rec): rec for rec in records}
        for idx, fut in enumerate(as_completed(futures), 1):
            try:
                ok, msg = fut.result()
            except Exception as exc:  # noqa: BLE001
                ok, msg = False, f"unhandled: {exc}"
            if ok:
                stats.success += 1
            elif msg == "429" or msg == "exhausted_retries":
                stats.rate_limited += 1
            else:
                stats.failed += 1
            if idx % 200 == 0:
                LOGGER.info(
                    "[Push] 进度 %s/%s ok=%s 429=%s fail=%s",
                    idx,
                    len(records),
                    stats.success,
                    stats.rate_limited,
                    stats.failed,
                )

    LOGGER.info(
        "[Push] 完成 ok=%s 429=%s fail=%s",
        stats.success,
        stats.rate_limited,
        stats.failed,
    )
    return stats


def setup_logging(verbose: bool, log_file: Optional[str]) -> None:
    fmt = "%(asctime)s [%(levelname)s] %(message)s"
    handlers: List[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if log_file:
        handlers.append(logging.FileHandler(log_file, encoding="utf-8"))
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format=fmt,
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=handlers,
        force=True,
    )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="B 站评论爬虫 + StarShield 推送（先落本地 JSONL，再推后端走 Bloom+模型链路）"
    )
    p.add_argument(
        "--bvid",
        action="append",
        default=[],
        help="BV 号（可多次指定，如 --bvid BV1xx --bvid BV2xx）",
    )
    p.add_argument(
        "--bvid-file",
        default=None,
        help="文本文件，一行一个 BV 号（# 开头注释会被跳过）",
    )
    p.add_argument("--target-count", type=int, default=10000, help="累计抓取多少条评论后停止（默认 10000）")
    p.add_argument("--ps", type=int, default=20, help="B 站每页条数（2024 起 /x/v2/reply 上限收紧到 20）")
    p.add_argument("--sort", type=int, default=3, help="新接口 mode：2=按时间, 3=按热度（默认 3）")
    p.add_argument("--max-pages", type=int, default=200, help="单个 BV 最多翻多少页")
    p.add_argument(
        "--sleep-between-pages",
        type=float,
        default=0.35,
        help="同一个 BV 翻页之间的休眠秒数（防 B 站风控）",
    )
    p.add_argument("--cookie", default=None, help="可选：B 站 Cookie，提升抓取上限")
    p.add_argument(
        "--out-jsonl",
        default="bilichat-ingest/data/bili_comments.jsonl",
        help="本地落盘文件（JSONL，每行一条）",
    )

    # 推送阶段参数
    p.add_argument(
        "--base-url",
        default=os.environ.get("STSHIELD_BASE", "http://127.0.0.1:8080/api"),
        help="StarShield 后端 base url，默认读环境变量 STSHIELD_BASE",
    )
    p.add_argument("--platform", default="BILIBILI", help="后端 platform 字段，默认 BILIBILI")
    p.add_argument("--rps", type=float, default=50.0, help="推送 QPS 上限，建议 < ip-qps(默认 300)")
    p.add_argument("--workers", type=int, default=16, help="推送线程数")
    p.add_argument("--request-timeout", type=float, default=5.0, help="单次 POST 超时（秒）")
    p.add_argument("--max-retries", type=int, default=3, help="429/5xx 时的重试次数")
    p.add_argument(
        "--push-limit",
        type=int,
        default=None,
        help="只推送本地 JSONL 的前 N 条（调试用，默认全部推）",
    )

    p.add_argument("--skip-fetch", action="store_true", help="跳过抓取，仅把现有 JSONL 推送到后端")
    p.add_argument("--skip-push", action="store_true", help="只抓不推，方便先看一眼数据再决定")
    p.add_argument("--dry-run", action="store_true", help="同时跳过抓取与推送，仅做参数校验")
    p.add_argument("-v", "--verbose", action="store_true", help="打开 DEBUG 日志")
    p.add_argument("--log-file", metavar="PATH", default=None, help="把日志同时写入文件")

    return p.parse_args()


def main() -> None:
    args = parse_args()
    setup_logging(args.verbose, args.log_file)

    if args.dry_run:
        LOGGER.info("[Main] dry-run 模式，仅打印参数")
        LOGGER.info("[Main] args=%s", vars(args))
        return

    out_path = Path(args.out_jsonl)

    if not args.skip_fetch:
        bvids = load_bvids(args.bvid, args.bvid_file)
        LOGGER.info("[Main] BV 列表共 %s 个，目标 %s 条", len(bvids), args.target_count)
        crawl_to_local(
            bvids,
            out_jsonl=out_path,
            cookie=args.cookie,
            target_count=args.target_count,
            ps=args.ps,
            sort=args.sort,
            max_pages=args.max_pages,
            sleep_between_pages=args.sleep_between_pages,
        )
    else:
        if not out_path.exists():
            raise SystemExit(f"--skip-fetch 模式下 {out_path} 不存在，请先抓取或换路径")
        LOGGER.info("[Main] 跳过抓取，直接使用 %s", out_path)

    if args.skip_push:
        LOGGER.info("[Main] --skip-push 已设置，结束")
        return

    push_to_backend(
        out_path,
        base_url=args.base_url,
        rps=args.rps,
        workers=args.workers,
        request_timeout=args.request_timeout,
        max_retries=args.max_retries,
        platform=args.platform,
        limit=args.push_limit,
    )


if __name__ == "__main__":
    main()
