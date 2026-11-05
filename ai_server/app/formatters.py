"""조회 결과의 금액·시각 표기. LLM 에게도 같은 문구를 전달합니다."""

import re
from datetime import datetime, timedelta, timezone
from typing import Any

KST = timezone(timedelta(hours=9))


def won(value: Any) -> str:
    return f"{int(value):,}원"


def eok(value: Any) -> str:
    """원 → 억 원. 금액이 없으면 '자료 없음'."""
    if value is None:
        return "자료 없음"
    amount = int(value)
    digits = 1 if abs(amount) < 1_000_000_000 else 0
    sign = "-" if amount < 0 else ""
    return f"{sign}{abs(amount) / 100_000_000:,.{digits}f}억 원"


def rate(value: Any) -> str:
    return f"{float(value):+.2f}%"


def kst_text(value: Any) -> str | None:
    """ISO 시각 → '11월 4일 15:30:00'. 읽을 수 없으면 None."""
    if not value:
        return None
    text = re.sub(r"(\.\d{6})\d+", r"\1", str(value)).replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KST)
    local = parsed.astimezone(KST)
    return f"{local.month}월 {local.day}일 {local:%H:%M:%S}"
