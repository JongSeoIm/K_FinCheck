"""RAG 공통 도구 (사업보고서 RAG 가 사용).

- 청크: 긴 글을 정해진 길이로 겹쳐 나눈다.
- 벡터화: OpenAI text-embedding-3-small. 같은 문장은 SQLite 캐시를 재사용해 비용을 다시 내지 않는다.
- 유사도: 코사인 유사도. 임베딩을 못 쓰면 글자 2-gram 겹침으로 대신한다.
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path

from app.usage import current_usage


CHUNK_SIZE = 500
CHUNK_OVERLAP = 100
EMBEDDING_MODEL = "text-embedding-3-small"


@contextmanager
def open_db(path):
    """커밋하고 반드시 닫는다. sqlite3 의 with 문은 닫지 않아 Windows 에서 파일이 잠긴 채 남는다."""
    db = sqlite3.connect(path)
    try:
        with db:
            yield db
    finally:
        db.close()


def chunk_text(text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[str]:
    text = re.sub(r"\s+", " ", text or "").strip()
    if len(text) <= size:
        return [text] if text else []
    pieces, start = [], 0
    while start < len(text):
        pieces.append(text[start:start + size])
        if start + size >= len(text):
            break
        start += size - overlap
    return pieces


def _bigrams(text: str) -> set[str]:
    compact = re.sub(r"[^0-9A-Za-z가-힣]", "", text.casefold())
    return {compact[i:i + 2] for i in range(len(compact) - 1)}


def lexical_similarity(question: str, text: str) -> float:
    q, t = _bigrams(question), _bigrams(text)
    return len(q & t) / len(q) if q else 0.0


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    norm = math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b))
    return dot / norm if norm else 0.0


class EmbeddingCache:
    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with open_db(self._path) as db:
            db.execute("CREATE TABLE IF NOT EXISTS embedding (key TEXT PRIMARY KEY, vector TEXT NOT NULL,"
                       " created_at TEXT DEFAULT CURRENT_TIMESTAMP)")

    @staticmethod
    def key(model: str, text: str) -> str:
        return hashlib.sha256(f"{model}\n{text}".encode()).hexdigest()

    def get_many(self, keys: list[str]) -> dict[str, list[float]]:
        if not keys:
            return {}
        with self._lock, open_db(self._path) as db:
            rows = db.execute(f"SELECT key, vector FROM embedding WHERE key IN ({','.join('?' * len(keys))})", keys).fetchall()
        return {key: json.loads(vector) for key, vector in rows}

    def put_many(self, items: dict[str, list[float]]) -> None:
        with self._lock, open_db(self._path) as db:
            db.executemany("INSERT OR REPLACE INTO embedding(key, vector) VALUES (?, ?)",
                           [(key, json.dumps(vector)) for key, vector in items.items()])


class OpenAIEmbedder:
    def __init__(self, client, model: str = EMBEDDING_MODEL, cache: EmbeddingCache | None = None) -> None:
        self._client = client
        self._model = model
        self._cache = cache

    @property
    def model(self) -> str:
        return self._model

    async def embed(self, texts: list[str]) -> list[list[float]]:
        keys = [EmbeddingCache.key(self._model, text) for text in texts]
        found = self._cache.get_many(keys) if self._cache else {}
        missing = [(key, text) for key, text in zip(keys, texts) if key not in found]
        if missing:
            unique = list(dict(missing).items())
            # 사업보고서처럼 청크가 많을 때를 위해 100개씩 나눠 요청한다(요청당 입력 수·토큰 한도).
            for start in range(0, len(unique), 100):
                batch = unique[start:start + 100]
                response = await self._client.embeddings.create(model=self._model, input=[text for _, text in batch], timeout=30)
                current_usage().add_embedding(self._model, getattr(response.usage, "total_tokens", 0))
                fresh = {key: item.embedding for (key, _), item in zip(batch, response.data)}
                if self._cache:
                    self._cache.put_many(fresh)
                found.update(fresh)
        return [found[key] for key in keys]
