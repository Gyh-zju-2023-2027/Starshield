import argparse
import csv
import random
from pathlib import Path


TOXIC_TEMPLATES = [
    "你这操作真{insult}",
    "{target}都是{insult}",
    "再送就{curse}",
    "这把输了都怪{target}",
    "你会不会玩，{insult}",
    "挂机狗{insult}",
    "你这种人就该{curse}",
    "打得跟{insult}一样",
    "别演了，{insult}",
    "你全家都{curse}",
    "菜就多练，{insult}",
]

BENIGN_TEMPLATES = [
    "这把打得不错，继续加油",
    "中路来支援一下",
    "先发育别上头",
    "兄弟稳一点能翻盘",
    "这波团战配合很好",
    "我们下一条龙集合",
    "你守高地我去带线",
    "别急，等对面失误",
    "这个阵容后期更强",
    "没事，下把继续",
    "谢谢你刚才的保护",
]

INSULT_WORDS = [
    "废物",
    "弱智",
    "脑残",
    "菜狗",
    "垃圾",
    "白痴",
    "孤儿",
]

CURSE_WORDS = [
    "去死",
    "死全家",
    "原地爆炸",
    "滚出游戏",
]

TARGET_WORDS = [
    "你",
    "对面打野",
    "这个射手",
    "你们辅助",
    "这个中单",
]

# 常见谐音/错字替换（可继续扩充）
HOMOPHONE_MAP = {
    "家": ["价", "佳", "嘉"],
    "死": ["四", "斯", "寺"],
    "完": ["万", "玩", "丸"],
    "妈": ["马", "麻"],
    "滚": ["衮", "棍"],
    "弱": ["若"],
    "智": ["治", "知"],
    "废": ["费"],
}


def make_toxic_sentence(rng: random.Random) -> str:
    tpl = rng.choice(TOXIC_TEMPLATES)
    return tpl.format(
        insult=rng.choice(INSULT_WORDS),
        curse=rng.choice(CURSE_WORDS),
        target=rng.choice(TARGET_WORDS),
    )


def make_homophone_variant(text: str, rng: random.Random, p: float = 0.45) -> str:
    chars = []
    for ch in text:
        if ch in HOMOPHONE_MAP and rng.random() < p:
            chars.append(rng.choice(HOMOPHONE_MAP[ch]))
        else:
            chars.append(ch)
    return "".join(chars)


def inject_game_context(text: str, rng: random.Random) -> str:
    prefix = rng.choice(
        ["排位里", "这把", "团战时", "高地前", "龙坑边", "开黑的时候", "逆风局里", ""]
    )
    if not prefix:
        return text
    return f"{prefix}{text}"


def build_samples(size: int, toxic_ratio: float, seed: int):
    rng = random.Random(seed)
    rows = []
    toxic_count = int(size * toxic_ratio)

    for _ in range(toxic_count):
        raw = inject_game_context(make_toxic_sentence(rng), rng)
        rows.append({"text": raw, "label": 1, "source": "game_synth_raw"})
        rows.append(
            {
                "text": make_homophone_variant(raw, rng),
                "label": 1,
                "source": "game_synth_homophone",
            }
        )

    while len(rows) < size:
        rows.append(
            {
                "text": inject_game_context(rng.choice(BENIGN_TEMPLATES), rng),
                "label": 0,
                "source": "game_synth_benign",
            }
        )

    rng.shuffle(rows)
    return rows[:size]


def write_csv(rows, output: Path):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["text", "label", "source"])
        writer.writeheader()
        writer.writerows(rows)


def parse_args():
    parser = argparse.ArgumentParser(description="生成游戏场景审核训练样本（含谐音增强）")
    parser.add_argument("--output", default="../data/game_dataset/game_synth.csv", help="输出CSV路径")
    parser.add_argument("--size", type=int, default=8000, help="总样本数")
    parser.add_argument("--toxic-ratio", type=float, default=0.55, help="有害样本占比")
    parser.add_argument("--seed", type=int, default=42, help="随机种子")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    rows = build_samples(size=args.size, toxic_ratio=args.toxic_ratio, seed=args.seed)
    output_path = Path(args.output).resolve()
    write_csv(rows, output_path)
    print(f"generated {len(rows)} rows -> {output_path}")
