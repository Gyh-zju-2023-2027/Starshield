"""一键验证：本地 JSONL 已推送到后端后，看下布隆+模型链路的处理结果。

用法：
    python verify_pipeline.py
    python verify_pipeline.py --base-url http://127.0.0.1:8080/api
    python verify_pipeline.py --watch     # 每 3 秒刷新一次，看实时消费进度

它做两件事：
  1) 调 ``/api/dashboard/metrics`` 看总量 / BLOCK / REVIEW / 阻断率；
  2) 抽样最近 100 条，按 ``platform=BILIBILI`` 过滤，统计不同 decision 的占比，
     并打印 5 条 BLOCK + 5 条 REVIEW 样本，肉眼复核引擎判断质量。

@author AI (under P10 supervision)
"""

from __future__ import annotations

import argparse
import json
import time
from collections import Counter
from typing import Any, Dict, List

import requests


def fetch_metrics(base_url: str, timeout: float = 5.0) -> Dict[str, Any]:
    resp = requests.get(base_url.rstrip("/") + "/dashboard/metrics", timeout=timeout)
    resp.raise_for_status()
    body = resp.json()
    if body.get("code") != 200:
        raise RuntimeError(f"metrics 返回异常: {body}")
    return body.get("data") or {}


def summarize(data: Dict[str, Any], platform_filter: str = "BILIBILI") -> None:
    total = data.get("total", 0)
    blocked = data.get("blocked", 0)
    review = data.get("review", 0)
    block_rate = data.get("blockRate", 0)

    print("=" * 60)
    print(f"全局总量: {total}")
    print(f"BLOCK   : {blocked}")
    print(f"REVIEW  : {review}")
    print(f"阻断率  : {block_rate:.2f}%")
    print("=" * 60)

    latest: List[Dict[str, Any]] = data.get("latest") or []
    bili = [m for m in latest if m.get("platform") == platform_filter]
    if not bili:
        print(f"(最近 100 条中暂无 platform={platform_filter} 的样本)")
        return

    decisions = Counter()
    pending = 0
    for m in bili:
        d = m.get("decision")
        if not d:
            pending += 1
        else:
            decisions[d] += 1

    print(f"最近 {len(bili)} 条 {platform_filter} 样本:")
    for k in ("PASS", "REVIEW", "BLOCK"):
        cnt = decisions.get(k, 0)
        pct = (cnt * 100 / len(bili)) if bili else 0
        print(f"  {k:<7}: {cnt:>4}  ({pct:5.2f}%)")
    if pending:
        print(f"  待处理 : {pending:>4}（消息已入库但 AI 还在跑）")

    def _show(decision: str, n: int = 5) -> None:
        items = [m for m in bili if m.get("decision") == decision][:n]
        if not items:
            return
        print(f"\n--- 抽样 {decision} ---")
        for m in items:
            text = (m.get("content") or "").replace("\n", " ")
            if len(text) > 60:
                text = text[:60] + "…"
            risk = m.get("riskScore")
            hits = m.get("hitWords") or "-"
            labels = m.get("labels") or "-"
            print(f"  [risk={risk:>3}] hits={hits} labels={labels}")
            print(f"     {text}")

    _show("BLOCK")
    _show("REVIEW")


def main() -> None:
    parser = argparse.ArgumentParser(description="StarShield 链路验证")
    parser.add_argument(
        "--base-url",
        default="http://127.0.0.1:8080/api",
        help="后端 base url",
    )
    parser.add_argument(
        "--platform",
        default="BILIBILI",
        help="抽样统计时仅看哪个 platform，默认 BILIBILI",
    )
    parser.add_argument(
        "--watch",
        action="store_true",
        help="持续刷新（按 Ctrl+C 退出）",
    )
    parser.add_argument("--interval", type=float, default=3.0, help="--watch 刷新间隔（秒）")
    args = parser.parse_args()

    while True:
        try:
            data = fetch_metrics(args.base_url)
            print(f"\n[{time.strftime('%H:%M:%S')}] base_url={args.base_url}")
            summarize(data, platform_filter=args.platform)
        except Exception as exc:  # noqa: BLE001
            print(f"[ERR] {exc}")
        if not args.watch:
            break
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
