"""5단계. 제목 기준 관련도 분류(본문 분석·점수 계산·임베딩 없음)."""
from __future__ import annotations

import re
from functools import lru_cache

from news.dictionary import StockEntry


DIRECT = "direct"
INDUSTRY = "industry"
NONE = "none"
LABELS = {DIRECT: "직접 관련", INDUSTRY: "산업 관련", NONE: ""}


@lru_cache(maxsize=512)
def _pattern(word: str) -> re.Pattern[str]:
    """대소문자를 구분하지 않는다. 영문으로 시작·끝나는 단어는 다른 영어 단어 안에서 찾지 않는다.

    예: 'Kia'는 'Nokia'·'Slovakia'에서, 'ESS'는 'Business'에서 찾지 않는다. 'HBM4'·'NAVER웹툰'은 찾는다.
    """
    before = r"(?<![A-Za-z])" if re.match(r"[A-Za-z]", word) else ""
    after = r"(?![A-Za-z])" if re.search(r"[A-Za-z]$", word) else ""
    return re.compile(before + re.escape(word) + after, re.IGNORECASE)


def _contains(text: str, word: str) -> bool:
    return _pattern(word).search(text) is not None


def classify(title: str, stock: StockEntry) -> str:
    """제목에 종목명·별칭이 있으면 직접 관련, 산업 키워드만 있으면 산업 관련."""
    for phrase in stock.excludes:
        title = _pattern(phrase).sub(" ", title)
    if any(_contains(title, alias) for alias in stock.aliases):
        return DIRECT
    if any(_contains(title, keyword) for keyword in stock.industry_keywords):
        return INDUSTRY
    return NONE
