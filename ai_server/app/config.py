from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_FILE = Path(__file__).resolve().parents[1] / ".env"


class Settings(BaseSettings):
    """환경 변수 또는 ai_server/.env 에서 읽는 설정."""

    model_config = SettingsConfigDict(env_file=ENV_FILE, extra="ignore")

    ai_mode: Literal["openai", "mock"] = "openai"
    openai_api_key: str = ""
    openai_model: str = "gpt-5.4-mini"
    openai_timeout_seconds: float = 20
    spring_base_url: str = "http://localhost:8080"
    tool_timeout_seconds: float = 8
