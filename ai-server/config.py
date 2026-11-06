"""설정값 (ai-server/.env → 없으면 ../backend/.env 순서로 읽는다)"""

import os
from pathlib import Path

from dotenv import dotenv_values

BASE_DIR = Path(__file__).resolve().parent
_files = [BASE_DIR / ".env", BASE_DIR.parent / "backend" / ".env"]
_values: dict[str, str] = {}
for _file in reversed(_files):  # 앞쪽 파일이 우선
    if _file.exists():
        _values.update({k: v for k, v in dotenv_values(_file).items() if v})


def _get(name: str, default: str = "") -> str:
    # 운영 환경 변수 > ai-server/.env > backend/.env > 기본값
    return os.environ.get(name) or _values.get(name) or default


OPENAI_API_KEY = _get("OPENAI_API_KEY")
OPENAI_MODEL = _get("OPENAI_MODEL", "gpt-5.4-mini")
OPENAI_REASONING_EFFORT = _get("OPENAI_REASONING_EFFORT", "low")
OPENAI_TIMEOUT_SECONDS = float(_get("OPENAI_TIMEOUT_SECONDS", "40"))
OPENAI_MAX_OUTPUT_TOKENS = int(_get("OPENAI_MAX_OUTPUT_TOKENS", "2000"))
OPENAI_INTERPRET_TIMEOUT_SECONDS = float(_get("OPENAI_INTERPRET_TIMEOUT_SECONDS", "8"))
OPENAI_INTERPRET_MAX_TOKENS = int(_get("OPENAI_INTERPRET_MAX_TOKENS", "800"))

SPRING_BASE_URL = _get("SPRING_BASE_URL", "http://localhost:8085").rstrip("/")
AI_INTERNAL_KEY = _get("AI_INTERNAL_KEY")
TOOL_TIMEOUT_SECONDS = float(_get("TOOL_TIMEOUT_SECONDS", "5"))

MAX_HISTORY = 10  # 팀 결정: 최근 메시지 10개
