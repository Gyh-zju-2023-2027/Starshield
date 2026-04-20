import os
from pathlib import Path

import joblib
from flask import Flask, jsonify, request

try:
    from pypinyin import lazy_pinyin
except ImportError:
    lazy_pinyin = None


MODEL_DIR = Path(os.getenv("MODEL_DIR", Path(__file__).resolve().parent))
VECTORIZER_PATH = MODEL_DIR / "vectorizer.pkl"
CLASSIFIER_PATH = MODEL_DIR / "classifier.pkl"

app = Flask(__name__)
USE_PINYIN = os.getenv("USE_PINYIN", "true").lower() in {"1", "true", "yes", "on"}


def load_models():
    if not VECTORIZER_PATH.exists():
        raise FileNotFoundError(f"未找到向量器模型: {VECTORIZER_PATH}")
    if not CLASSIFIER_PATH.exists():
        raise FileNotFoundError(f"未找到分类器模型: {CLASSIFIER_PATH}")
    vectorizer = joblib.load(VECTORIZER_PATH)
    classifier = joblib.load(CLASSIFIER_PATH)
    return vectorizer, classifier


vectorizer, classifier = load_models()


def preprocess_text(text: str) -> str:
    clean = str(text).strip()
    if not clean:
        return ""
    if not USE_PINYIN or lazy_pinyin is None:
        return clean
    pinyin_text = " ".join(lazy_pinyin(clean))
    return f"{clean} {pinyin_text}"


@app.get("/health")
def health():
    return jsonify({
        "status": "ok",
        "usePinyin": USE_PINYIN,
        "pinyinReady": lazy_pinyin is not None
    })


@app.post("/score")
def score():
    body = request.get_json(silent=True) or {}
    text = str(body.get("text", "")).strip()
    if not text:
        return jsonify({"error": "text is required"}), 400

    text_processed = preprocess_text(text)
    text_vec = vectorizer.transform([text_processed])
    probability = float(classifier.predict_proba(text_vec)[0][1])
    probability = max(0.0, min(1.0, probability))
    return jsonify({"score": probability})


if __name__ == "__main__":
    port = int(os.getenv("PORT", "5000"))
    app.run(host="0.0.0.0", port=port)
