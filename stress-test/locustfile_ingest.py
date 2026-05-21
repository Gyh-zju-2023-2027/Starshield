

import random
from locust import HttpUser, task, between, LoadTestShape

# ─────────────────────────────────────────────────────────
# 内容池：覆盖正常 / 边界 / 违规三类，触发引擎 A & B 不同路径
# ─────────────────────────────────────────────────────────
CONTENT_POOL = [
    # 正常消息 → 引擎A PASS，引擎B 轻量打分低 → 最终 PASS
    "这把稳赢，兄弟们跟上！",
    "有没有人组队打副本？",
    "刚刚那波操作太帅了！",
    "求大佬带练，萌新在线等",
    "服务器又卡了吗？",
    "A" * 500,    # 中等长度，测序列化耗时
    "B" * 3000,   # 接近上限，测 MQ 消息体大小
    # 边界消息 → 触发引擎A REVIEW
    "这游戏有点问题吧？感觉有人开挂",
    "举报这个玩家，太离谱了",
    # 违规消息 → 触发引擎A BLOCK（含敏感词，跳过引擎B深度分析）
    "你这个笨蛋滚出去",
    "fuck this game 垃圾",
]

PLATFORMS = ["GAME_INNER", "BILIBILI", "WEIBO", "DOUYIN", "OTHER"]

# 每个 VU 独占的 playerId 数量
# player-qps=30（1s 窗口），单 VU 理论最高 30 req/s
# 设为 50 → 同一 VU 内单个玩家平均每秒 < 1 次，完全不触发玩家限流
PLAYER_POOL_PER_VU = 50


# ─────────────────────────────────────────────────────────
# 虚拟用户
# ─────────────────────────────────────────────────────────
class IngestUser(HttpUser):
    wait_time = between(0, 0)   # 全速压测；如需限速改为 between(0.05, 0.1)

    def on_start(self):
        """
        每个 VU 启动时生成独立玩家池。
        player_id 格式：vu_{user_id}_{序号}
        确保不同 VU 的玩家 ID 完全不重叠，消除跨 VU 的 player 限流干扰。
        """
        uid = id(self) % 100000   # 用对象地址低位作为 VU 唯一标识
        self.player_pool = [f"vu{uid}_p{i:04d}" for i in range(PLAYER_POOL_PER_VU)]

    @task
    def upload_chat_message(self):
        # ── 1. 伪造随机 IP（模拟来自全球不同 IP 的流量） ──────────────
        # 避开 0.x 和 127.x，使用合法单播段
        fake_ip = (
            f"{random.randint(1, 223)}."
            f"{random.randint(0, 255)}."
            f"{random.randint(0, 255)}."
            f"{random.randint(1, 254)}"
        )

        # ── 2. 从本 VU 私有池随机选取 playerId ───────────────────────
        player_id = random.choice(self.player_pool)

        # ── 3. 严格按 api-spec.yaml 构造 Payload ─────────────────────
        #    ChatMessageUploadRequest required: playerId, content, platform
        payload = {
            "playerId": player_id,
            "content":  random.choice(CONTENT_POOL),
            "platform": random.choice(PLATFORMS),
        }

        # ── 4. 发送请求，标记 Locust 统计桶 ──────────────────────────
        with self.client.post(
            "/api/chat/upload",
            json=payload,
            headers={"X-Forwarded-For": fake_ip},
            name="/api/chat/upload",      # Locust UI 中的聚合名
            catch_response=True,
        ) as resp:
            if resp.status_code != 200:
                resp.failure(f"HTTP {resp.status_code}")
                return
            try:
                body = resp.json()
            except Exception:
                resp.failure("响应非 JSON")
                return

            code = body.get("code")
            if code == 200:
                resp.success()
            elif code == 429:
                # 将限流单独标记为 failure，方便在 Locust 图表里区分
                # 注意：HTTP 状态码仍是 200，Locust 默认不识别业务限流
                resp.failure(f"业务限流(429): {body.get('message')}")
            else:
                resp.failure(f"业务异常 code={code}: {body.get('message')}")


# ─────────────────────────────────────────────────────────
# Shape 一：阶梯加压
# 用途：找到系统吞吐量拐点（P99 开始陡升 or 429 率开始飙升的并发数）
#
# 阶段  时长  VU    每秒新增
#   0    20s   50    10
#   1    20s  150    10
#   2    20s  300    10
#   3    20s  500    10
#   4    30s  500     0   ← 观察稳定期 MQ 堆积
#   5    10s    0     0   ← 结束
# ─────────────────────────────────────────────────────────
class StairShape(LoadTestShape):
    stages = [
        {"duration": 20,  "users":  50,  "spawn_rate": 10},
        {"duration": 40,  "users": 150,  "spawn_rate": 10},
        {"duration": 60,  "users": 300,  "spawn_rate": 10},
        {"duration": 80,  "users": 500,  "spawn_rate": 10},
        {"duration": 110, "users": 500,  "spawn_rate":  1},  # 稳压观察期
        {"duration": 120, "users":   0,  "spawn_rate": 50},  # 收尾
    ]

    def tick(self):
        run_time = self.get_run_time()
        for stage in self.stages:
            if run_time < stage["duration"]:
                return stage["users"], stage["spawn_rate"]
        return None   # 结束测试


# ─────────────────────────────────────────────────────────
# Shape 二：峰值冲击
# 用途：验证系统从低负载瞬间被打到最高并发时的弹性恢复能力
#       以及 MQ 堆积后能否在流量回落后追平消费
#
# 阶段  时长   VU    说明
#   0    20s   50   基准热身
#   1    10s  600   瞬时峰值冲击
#   2    20s   50   恢复观察（MQ 能否消化堆积）
#   3    10s  600   二次冲击
#   4    20s   50   最终恢复
# ─────────────────────────────────────────────────────────
class SpikeShape(LoadTestShape):
    stages = [
        {"duration": 20,  "users":  50, "spawn_rate": 10},
        {"duration": 30,  "users": 600, "spawn_rate": 300},  # 瞬时拉起
        {"duration": 50,  "users":  50, "spawn_rate": 300},  # 快速回落
        {"duration": 60,  "users": 600, "spawn_rate": 300},  # 二次冲击
        {"duration": 80,  "users":  50, "spawn_rate": 300},  # 再次回落
        {"duration": 90,  "users":   0, "spawn_rate": 50},
    ]

    def tick(self):
        run_time = self.get_run_time()
        for stage in self.stages:
            if run_time < stage["duration"]:
                return stage["users"], stage["spawn_rate"]
        return None




# shape_class = StairShape    # 阶梯加压
# shape_class = SpikeShape    # 峰值冲击