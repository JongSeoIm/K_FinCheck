"""지원 종목과 이름 인식. 지원 종목은 Spring 쪽과 같은 3개입니다."""
import re

COMPANIES = {
    "005930": {"name": "삼성전자", "market": "KOSPI", "aliases": ["삼성전자", "삼성"]},
    "000660": {"name": "SK하이닉스", "market": "KOSPI", "aliases": ["SK하이닉스", "에스케이하이닉스", "하이닉스"]},
    "035420": {"name": "NAVER", "market": "KOSPI", "aliases": ["NAVER", "네이버"]},
}

# 아직 지원하지 않는 종목 이름 (이름이 나오면 지원 범위를 안내합니다)
UNSUPPORTED_NAMES = [
    "카카오뱅크", "카카오", "현대자동차", "현대차", "기아", "LG화학", "LG에너지솔루션", "삼성SDI",
    "삼성바이오로직스", "셀트리온", "POSCO", "포스코", "현대모비스", "KB금융", "신한지주",
    "LG전자", "SK텔레콤", "한국전력", "크래프톤", "하이브", "에코프로", "알테오젠", "테슬라", "애플",
]

CODE_RE = re.compile(r"(?<!\d)(\d{6})(?!\d)")


def find_companies(text: str) -> list[str]:
    """질문에 나온 지원 종목 코드를 나온 순서대로 돌려줍니다."""
    found: list[tuple[int, str]] = []
    lowered = text.lower()
    for code, info in COMPANIES.items():
        positions = [lowered.find(alias.lower()) for alias in info["aliases"]]
        positions = [p for p in positions if p >= 0]
        if code in text:
            positions.append(text.find(code))
        if positions:
            found.append((min(positions), code))
    return [code for _, code in sorted(found)]


def find_unsupported(text: str) -> str | None:
    """지원하지 않는 종목 이름이나 코드가 나오면 그 표현을 돌려줍니다."""
    for name in UNSUPPORTED_NAMES:
        if name.lower() in text.lower():
            # 지원 종목 이름의 일부(예: '삼성')와 겹치지 않도록 지원 종목이 함께 나온 경우는 제외
            if name == "삼성SDI" or not find_companies(text):
                return name
            if name in ("카카오", "카카오뱅크", "현대차", "현대자동차", "기아"):
                return name
    for match in CODE_RE.finditer(text):
        if match.group(1) not in COMPANIES:
            return match.group(1)
    return None
