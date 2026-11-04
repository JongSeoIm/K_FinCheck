"""설정. 값은 환경변수 또는 ai_server/.env 에서 읽습니다. 키는 코드·로그에 남기지 않습니다."""
import os
from pathlib import Path


def _load_env_file() -> None:
    path = Path(__file__).resolve().parent.parent / ".env"
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


_load_env_file()


class Settings:
    openai_api_key: str = os.environ.get("OPENAI_API_KEY", "")
    openai_model: str = os.environ.get("OPENAI_MODEL", "gpt-5.4-mini")
    openai_base_url: str = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
    spring_base_url: str = os.environ.get("SPRING_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    tool_timeout: float = float(os.environ.get("TOOL_TIMEOUT_SECONDS", "8"))
    llm_timeout: float = float(os.environ.get("LLM_TIMEOUT_SECONDS", "40"))
    internal_key: str = os.environ.get("AI_INTERNAL_KEY", "")
    history_limit: int = 10  # 최근 메시지 10개 (사용자·AI 합쳐서)


settings = Settings()
