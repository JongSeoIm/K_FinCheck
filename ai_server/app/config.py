from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


load_dotenv(Path(__file__).resolve().parents[1] / ".env")


@dataclass(frozen=True)
class Settings:
    ai_mode: str
    openai_api_key: str | None
    openai_model: str | None
    spring_base_url: str
    openai_interpret_model: str | None = None
    embedding_model: str = "text-embedding-3-small"
    rag_enabled: bool = True
    embedding_cache_path: str = "data/embeddings.sqlite3"
    report_store_path: str = "data/reports.sqlite3"
    report_store: str = "mariadb"   # mariadb(Spring 경유, 기본) 또는 sqlite(예전 파일 방식)

    @property
    def openai_configured(self) -> bool:
        return bool(self.openai_api_key and self.openai_model)


def get_settings() -> Settings:
    mode = os.getenv("AI_MODE", "mock").strip().lower()
    if mode not in {"mock", "openai"}:
        raise ValueError("AI_MODE는 mock 또는 openai여야 합니다.")

    return Settings(
        ai_mode=mode,
        openai_api_key=os.getenv("OPENAI_API_KEY") or None,
        openai_model=os.getenv("OPENAI_MODEL") or None,
        spring_base_url=os.getenv("SPRING_BASE_URL", "http://127.0.0.1:8080").rstrip("/"),
        openai_interpret_model=os.getenv("OPENAI_INTERPRET_MODEL") or None,
        embedding_model=os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
        rag_enabled=os.getenv("RAG_EMBEDDING_ENABLED", "true").strip().lower() != "false",
        embedding_cache_path=os.getenv("EMBEDDING_CACHE_PATH",
                                       str(Path(__file__).resolve().parents[1] / "data" / "embeddings.sqlite3")),
        report_store_path=os.getenv("REPORT_STORE_PATH",
                                    str(Path(__file__).resolve().parents[1] / "data" / "reports.sqlite3")),
        report_store=os.getenv("REPORT_STORE", "mariadb").strip().lower(),
    )
