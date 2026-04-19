import argparse
import csv
from pathlib import Path


def read_cold_csv(path: Path, text_col: str, label_col: str):
    rows = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            text = str(row.get(text_col, "")).strip()
            label = str(row.get(label_col, "")).strip()
            if not text or label not in {"0", "1"}:
                continue
            rows.append({"text": text, "label": int(label), "source": "cold"})
    return rows


def read_game_csv(path: Path):
    rows = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            text = str(row.get("text", "")).strip()
            label = str(row.get("label", "")).strip()
            source = str(row.get("source", "game")).strip() or "game"
            if not text or label not in {"0", "1"}:
                continue
            rows.append({"text": text, "label": int(label), "source": source})
    return rows


def write_rows(rows, output: Path):
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["text", "label", "source"])
        writer.writeheader()
        writer.writerows(rows)


def parse_args():
    parser = argparse.ArgumentParser(description="合并 COLD + 游戏语料为训练集")
    parser.add_argument("--cold-train", required=True, help="COLD train.csv 路径")
    parser.add_argument("--cold-text-col", default="TEXT", help="COLD 文本列名")
    parser.add_argument("--cold-label-col", default="label", help="COLD 标签列名")
    parser.add_argument("--game-data", required=True, help="游戏增强数据 CSV")
    parser.add_argument("--output", default="../data/game_dataset/train_merged.csv", help="输出训练集")
    parser.add_argument("--game-weight", type=int, default=2, help="游戏数据重复采样权重")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()

    cold_rows = read_cold_csv(Path(args.cold_train), args.cold_text_col, args.cold_label_col)
    game_rows = read_game_csv(Path(args.game_data))
    merged_rows = cold_rows + game_rows * max(1, args.game_weight)

    write_rows(merged_rows, Path(args.output).resolve())
    print(f"cold={len(cold_rows)} game={len(game_rows)} weight={args.game_weight}")
    print(f"merged={len(merged_rows)} -> {Path(args.output).resolve()}")
