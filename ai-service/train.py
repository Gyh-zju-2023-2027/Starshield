import argparse
import csv
import json
from pathlib import Path

import joblib
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, roc_auc_score
from sklearn.model_selection import train_test_split

try:
    from pypinyin import lazy_pinyin
except ImportError:
    lazy_pinyin = None


def normalize_label(raw):
    value = str(raw).strip().lower()
    if value in {"1", "true", "yes", "block", "review", "abuse", "spam", "toxic", "违规"}:
        return 1
    if value in {"0", "false", "no", "pass", "normal", "clean", "正常"}:
        return 0
    raise ValueError(f"无法识别的标签值: {raw}")


def load_dataset(path: Path, text_col: str, label_col: str):
    if not path.exists():
        raise FileNotFoundError(f"数据集不存在: {path}")

    texts = []
    labels = []

    if path.suffix.lower() == ".jsonl":
        with path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                item = json.loads(line)
                text = str(item.get(text_col, "")).strip()
                if not text:
                    continue
                label = normalize_label(item.get(label_col))
                texts.append(text)
                labels.append(label)
        return texts, labels

    with path.open("r", encoding="utf-8-sig", newline="") as f:
        sample = f.read(4096)
        f.seek(0)
        dialect = csv.Sniffer().sniff(sample, delimiters=",\t")
        reader = csv.DictReader(f, dialect=dialect)
        for row in reader:
            text = str(row.get(text_col, "")).strip()
            if not text:
                continue
            label = normalize_label(row.get(label_col))
            texts.append(text)
            labels.append(label)

    return texts, labels


def preprocess_text(text: str, use_pinyin: bool) -> str:
    clean = str(text).strip()
    if not clean:
        return ""
    if not use_pinyin or lazy_pinyin is None:
        return clean
    pinyin_text = " ".join(lazy_pinyin(clean))
    return f"{clean} {pinyin_text}"


def train(args):
    dataset = Path(args.input).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    texts, labels = load_dataset(dataset, args.text_col, args.label_col)
    if len(texts) < 20:
        raise ValueError("样本数量过少，至少需要20条以上样本")
    if args.use_pinyin and lazy_pinyin is None:
        raise ImportError("启用拼音特征需要先安装 pypinyin：pip install pypinyin")

    processed_texts = [preprocess_text(text, args.use_pinyin) for text in texts]

    x_train, x_val, y_train, y_val = train_test_split(
        processed_texts,
        labels,
        test_size=args.val_ratio,
        random_state=args.seed,
        stratify=labels,
    )

    vectorizer = TfidfVectorizer(
        analyzer=args.analyzer,
        ngram_range=(args.ngram_min, args.ngram_max),
        max_features=args.max_features,
        min_df=args.min_df,
        max_df=args.max_df,
        lowercase=True,
    )
    x_train_vec = vectorizer.fit_transform(x_train)
    x_val_vec = vectorizer.transform(x_val)

    classifier = LogisticRegression(
        C=2.0,
        solver="liblinear",
        max_iter=500,
        class_weight="balanced",
        random_state=args.seed,
    )
    classifier.fit(x_train_vec, y_train)

    val_pred = classifier.predict(x_val_vec)
    val_prob = classifier.predict_proba(x_val_vec)[:, 1]
    auc = roc_auc_score(y_val, val_prob)

    print("==== 验证集分类报告 ====")
    print(classification_report(y_val, val_pred, digits=4))
    print(f"Validation ROC-AUC: {auc:.4f}")

    vectorizer_path = output_dir / "vectorizer.pkl"
    classifier_path = output_dir / "classifier.pkl"
    metadata_path = output_dir / "training_metrics.json"

    joblib.dump(vectorizer, vectorizer_path)
    joblib.dump(classifier, classifier_path)

    metadata = {
        "dataset": str(dataset),
        "samples": len(texts),
        "positive_rate": round(sum(labels) / len(labels), 6),
        "val_ratio": args.val_ratio,
        "use_pinyin": args.use_pinyin,
        "seed": args.seed,
        "max_features": args.max_features,
        "analyzer": args.analyzer,
        "ngram_range": [args.ngram_min, args.ngram_max],
        "min_df": args.min_df,
        "max_df": args.max_df,
        "roc_auc": round(float(auc), 6),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"模型已保存: {vectorizer_path}")
    print(f"模型已保存: {classifier_path}")
    print(f"训练指标: {metadata_path}")


def build_arg_parser():
    parser = argparse.ArgumentParser(description="训练 StarShield 轻量审核模型（TF-IDF + LR）")
    parser.add_argument("--input", required=True, help="训练数据路径（csv/tsv/jsonl）")
    parser.add_argument("--text-col", default="text", help="文本列名")
    parser.add_argument("--label-col", default="label", help="标签列名")
    parser.add_argument("--output-dir", default=".", help="模型输出目录")
    parser.add_argument("--val-ratio", type=float, default=0.2, help="验证集比例")
    parser.add_argument("--max-features", type=int, default=120000, help="TF-IDF词表上限")
    parser.add_argument("--analyzer", default="char", choices=["word", "char", "char_wb"], help="向量化粒度")
    parser.add_argument("--ngram-min", type=int, default=2, help="ngram最小长度")
    parser.add_argument("--ngram-max", type=int, default=4, help="ngram最大长度")
    parser.add_argument("--min-df", type=int, default=1, help="最小文档频次")
    parser.add_argument("--max-df", type=float, default=0.995, help="最大文档占比")
    parser.add_argument("--use-pinyin", action="store_true", default=True, help="是否拼接拼音特征")
    parser.add_argument("--disable-pinyin", action="store_false", dest="use_pinyin", help="关闭拼音特征")
    parser.add_argument("--seed", type=int, default=42, help="随机种子")
    return parser


if __name__ == "__main__":
    train(build_arg_parser().parse_args())
