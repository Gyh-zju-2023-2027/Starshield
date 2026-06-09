"""IdentityV-weibo 数据集 → JSONL → StarShield /api/chat/upload。

数据来源：https://huggingface.co/datasets/JaydenChao101/IdentityV-weibo
入库格式对齐 chat_message_log：playerId / content / platform=WEIBO / createTime
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Dict, Iterator, List, Optional

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ingest_comments import PostStats, TokenBucket, post_chat, setup_logging

LOGGER = logging.getLogger("identityv_weibo_ingest")

CST = timezone(timedelta(hours=8))

HF_DATASET_CANDIDATES = [
    "https://huggingface.co/datasets/JaydenChao101/IdentityV-weibo/resolve/main/weibo_dataset.jsonl",
    "https://huggingface.co/datasets/JaydenChao101/IdentityV-weibo/resolve/main/identity_v_weibo.jsonl",
]
SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
DEFAULT_RAW = str(DATA_DIR / "weibo_dataset.jsonl")
DEFAULT_OUT = str(DATA_DIR / "identityv_weibo.jsonl")
TAG_RE = re.compile(r"<[^>]+>")


def strip_html(text: str) -> str:
    if not text:
        return ""
    cleaned = TAG_RE.sub("", text)
    return cleaned.replace("&nbsp;", " ").strip()


def parse_weibo_time(raw: Optional[str]) -> Optional[int]:
    if not raw:
        return None
    try:
        dt = parsedate_to_datetime(raw)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=CST)
        return int(dt.timestamp())
    except (TypeError, ValueError, OverflowError):
        return None


def fmt_create_time(ctime: Optional[int]) -> Optional[str]:
    if not ctime:
        return None
    try:
        return datetime.fromtimestamp(int(ctime), tz=CST).strftime("%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError, OSError, OverflowError):
        return None


def download_dataset(dest: Path, force: bool = False) -> None:
    if dest.exists() and not force:
        LOGGER.info("[Download] 本地已有 %s，跳过下载", dest)
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    last_err: Optional[Exception] = None
    for url in HF_DATASET_CANDIDATES:
        try:
            LOGGER.info("[Download] 尝试 %s", url)
            with requests.get(url, stream=True, timeout=120) as resp:
                resp.raise_for_status()
                with dest.open("wb") as fp:
                    for chunk in resp.iter_content(chunk_size=1024 * 256):
                        if chunk:
                            fp.write(chunk)
            LOGGER.info("[Download] 完成 size=%s bytes", dest.stat().st_size)
            return
        except Exception as exc:  # noqa: BLE001
            last_err = exc
            LOGGER.warning("[Download] 失败: %s", exc)
    raise RuntimeError(f"无法下载 IdentityV-weibo 数据集: {last_err}")


def iter_weibo_records(
    jsonl_path: Path,
    *,
    include_posts: bool = False,
    limit: Optional[int] = None,
) -> Iterator[Dict]:
    count = 0
    with jsonl_path.open("r", encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if not line:
                continue
            try:
                post = json.loads(line)
            except json.JSONDecodeError:
                continue

            if include_posts:
                post_text = strip_html(post.get("text") or "")
                if post_text:
                    yield {
                        "player_id": f"WEIBO_OFFICIAL_{post.get('post_id', 'x')}"[:64],
                        "text": post_text,
                        "ctime": parse_weibo_time(post.get("created_at")),
                        "source": "post",
                        "post_id": post.get("post_id"),
                    }
                    count += 1
                    if limit is not None and count >= limit:
                        return

            for comment in post.get("comments") or []:
                text = strip_html(comment.get("text") or "")
                if not text:
                    continue
                comment_id = str(comment.get("comment_id") or f"c_{count}")
                user_name = (comment.get("user_name") or "anon").replace(" ", "_")
                player_id = f"WEIBO_{user_name}_{comment_id}"[:64]
                yield {
                    "player_id": player_id,
                    "text": text,
                    "ctime": parse_weibo_time(comment.get("created_at")),
                    "source": "comment",
                    "comment_id": comment_id,
                    "post_id": comment.get("post_id") or post.get("post_id"),
                    "user_name": comment.get("user_name"),
                }
                count += 1
                if limit is not None and count >= limit:
                    return


def export_to_jsonl(
    src_jsonl: Path,
    out_jsonl: Path,
    *,
    target_count: int,
    include_posts: bool,
    task_id: Optional[str] = None,
) -> int:
    out_jsonl.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    seen: set[str] = set()
    if task_id:
        print(json.dumps({"event": "fetch_start", "task_id": task_id, "fetched": 0}, ensure_ascii=False), flush=True)
    with out_jsonl.open("w", encoding="utf-8") as fp:
        for record in iter_weibo_records(src_jsonl, include_posts=include_posts, limit=target_count):
            key = f"{record.get('comment_id') or record.get('post_id')}:{record['text'][:80]}"
            if key in seen:
                continue
            seen.add(key)
            fp.write(json.dumps(record, ensure_ascii=False) + "\n")
            total += 1
            if task_id and total % 100 == 0:
                print(json.dumps({"event": "progress", "task_id": task_id, "fetched": total}, ensure_ascii=False), flush=True)
            if total >= target_count:
                break
    LOGGER.info("[Export] 导出 %s 条 → %s", total, out_jsonl)
    if task_id:
        print(json.dumps({"event": "fetch_done", "task_id": task_id, "fetched": total}, ensure_ascii=False), flush=True)
    return total


def push_weibo_to_backend(
    in_jsonl: Path,
    *,
    base_url: str,
    rps: float,
    request_timeout: float,
    max_retries: int,
    limit: Optional[int] = None,
    task_id: Optional[str] = None,
) -> PostStats:
    stats = PostStats()
    bucket = TokenBucket(rate_per_sec=rps, capacity=max(rps, 1.0))
    sess = requests.Session()
    sess.trust_env = False

    records: List[Dict] = []
    with in_jsonl.open("r", encoding="utf-8") as fp:
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

    LOGGER.info("[Push] WEIBO 待推送 %s 条", len(records))
    if task_id:
        print(json.dumps({"event": "push_start", "task_id": task_id, "pushed": 0}, ensure_ascii=False), flush=True)

    for idx, record in enumerate(records, 1):
        text = (record.get("text") or "").strip()
        if not text:
            stats.failed += 1
            continue
        payload = {
            "playerId": record.get("player_id", f"WEIBO_{idx}")[:64],
            "content": text,
            "platform": "WEIBO",
        }
        create_time = fmt_create_time(record.get("ctime"))
        if create_time:
            payload["createTime"] = create_time

        ok = False
        for attempt in range(max_retries + 1):
            bucket.acquire()
            ok, msg = post_chat(sess, base_url, payload, timeout=request_timeout)
            if ok:
                stats.success += 1
                break
            if msg == "429" or msg.startswith("http_5"):
                time.sleep(min(0.5 * (2 ** attempt), 5.0))
                continue
            stats.failed += 1
            LOGGER.warning("[Push] 失败 idx=%s msg=%s", idx, msg)
            break
        else:
            stats.rate_limited += 1

        if task_id and idx % 100 == 0:
            print(json.dumps({
                "event": "progress",
                "task_id": task_id,
                "fetched": idx,
                "pushed": stats.success,
            }, ensure_ascii=False), flush=True)

    LOGGER.info("[Push] 完成 ok=%s fail=%s", stats.success, stats.failed)
    if task_id:
        print(json.dumps({"event": "finished", "task_id": task_id, "pushed": stats.success}, ensure_ascii=False), flush=True)
    return stats


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="IdentityV-weibo 数据集导入 StarShield")
    p.add_argument("--from-task", type=str, default=None, help="StarShield 爬取任务 ID")
    p.add_argument("--dataset-file", default=None, help="本地 weibo_dataset.jsonl 路径（默认自动下载）")
    p.add_argument("--download-only", action="store_true", help="仅下载数据集")
    p.add_argument("--force-download", action="store_true", help="强制重新下载")
    p.add_argument("--include-posts", action="store_true", help="同时导入官方帖子正文")
    p.add_argument("--target-count", type=int, default=5000, help="导入条数上限")
    p.add_argument("--out-jsonl", default=DEFAULT_OUT, help="转换后的 JSONL 输出路径")
    p.add_argument("--skip-export", action="store_true", help="跳过转换，直接推送已有 JSONL")
    p.add_argument("--skip-push", action="store_true", help="只导出 JSONL，不推送")
    p.add_argument(
        "--base-url",
        default=os.environ.get("STSHIELD_BASE", "http://127.0.0.1:8080/api"),
        help="StarShield 后端 base url",
    )
    p.add_argument("--rps", type=float, default=30.0)
    p.add_argument("--request-timeout", type=float, default=5.0)
    p.add_argument("--max-retries", type=int, default=3)
    p.add_argument("--push-limit", type=int, default=None)
    p.add_argument("-v", "--verbose", action="store_true")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    setup_logging(args.verbose, None)
    task_id = args.from_task

    raw_path = Path(args.dataset_file) if args.dataset_file else Path(DEFAULT_RAW)
    if not raw_path.exists() or args.force_download:
        download_dataset(raw_path, force=args.force_download)

    if args.download_only:
        LOGGER.info("[Main] 下载完成")
        return

    out_path = Path(args.out_jsonl)
    if not args.skip_export:
        total = export_to_jsonl(
            raw_path,
            out_path,
            target_count=args.target_count,
            include_posts=args.include_posts,
            task_id=task_id,
        )
        if total == 0:
            if task_id:
                print(json.dumps({
                    "event": "error",
                    "task_id": task_id,
                    "message": "未导出任何微博记录，请检查数据集文件",
                }, ensure_ascii=False), flush=True)
            raise SystemExit("未导出任何记录，请检查数据集文件")
    elif not out_path.exists():
        raise SystemExit(f"--skip-export 模式下 {out_path} 不存在")

    if args.skip_push:
        LOGGER.info("[Main] --skip-push，结束")
        return

    push_weibo_to_backend(
        out_path,
        base_url=args.base_url,
        rps=args.rps,
        request_timeout=args.request_timeout,
        max_retries=args.max_retries,
        limit=args.push_limit,
        task_id=task_id,
    )


if __name__ == "__main__":
    main()
