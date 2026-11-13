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
    openai_embedding_model: str
    spring_base_url: str

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
        openai_embedding_model=os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
        spring_base_url=os.getenv("SPRING_BASE_URL", "http://127.0.0.1:8080").rstrip("/"),
    )
