from __future__ import annotations

import re
from difflib import SequenceMatcher
from typing import Literal

from pydantic import BaseModel

from app.quote_tool import COMPANIES, ALIASES, named_codes, resolve_stock


class QuestionInterpretation(BaseModel):
    company: Literal["삼성전자", "SK하이닉스", "NAVER", "카카오", "현대차", "기아", "LG화학", "삼성SDI", "selected", "unknown"]
    intent: Literal["quote", "financial", "news", "composite", "concept", "unrelated", "clarify"]
    needs_clarification: bool
    period: Literal["current", "historical", "unspecified"]
    criteria: Literal["annual_consolidated", "unsupported", "unspecified"]


def normalize_question(message: str) -> str:
    message = re.sub(r"\s+", " ", message).strip()
    for typo, corrected in {"주까": "주가", "현제가": "현재가", "현재까": "현재가", "실쩍": "실적", "뉴수": "뉴스", "영업이익율": "영업이익률"}.items():
        message = message.replace(typo, corrected)
    return message


def validated_question(result: QuestionInterpretation, original: str, selected):
    if result.needs_clarification or result.company == "unknown" or result.intent == "clarify":
        raise ValueError("기업이나 질문의 의미를 정확히 확인하지 못했습니다. 기업명과 현재가·실적·뉴스 중 원하는 정보를 알려 주세요.")
    names = {name: code for code, name in COMPANIES.items()}
    code = selected.symbol if result.company == "selected" else names[result.company]
    if result.company == "selected":
        resolve_stock(original, selected)
    explicit = named_codes(original)
    if explicit and explicit != {code}:
        raise ValueError("질문에 나온 기업을 하나로 확인할 수 없습니다. 한 기업씩 질문해 주세요.")
    if result.company not in {"selected", "unknown"} and not explicit:
        try:
            resolve_stock(original, selected)
        except ValueError:
            tokens = re.findall(r"[가-힣A-Za-z]+", original.casefold())
            aliases = [alias for alias, target in ALIASES.items() if target == code]
            if not any(SequenceMatcher(None, token.removesuffix("의"), alias).ratio() >= .72 for token in tokens for alias in aliases):
                raise ValueError("기업명을 확실히 확인하지 못했습니다. 정확한 기업명이나 종목 코드를 알려 주세요.")
    if result.intent == "unrelated":
        return None
    if result.intent == "concept":
        return original
    if result.criteria == "unsupported":
        raise ValueError("해당 조회 조건은 아직 지원하지 않습니다. 연결 연간 실적 또는 현재 시세·관련 뉴스로 질문해 주세요.")
    if result.period == "historical" and result.intent != "financial":
        raise ValueError("과거 특정 기간의 시세·뉴스 조회는 아직 지원하지 않습니다. 현재 시점 기준으로 질문해 주세요.")
    if re.search(r"어제|지난|작년|최근\s*\d+|\d+\s*(일|주|개월)\s*(간|전)", original):
        raise ValueError("기간을 정확히 지정해 주세요. 현재 시세와 사업연도를 지정한 연간 실적을 지원합니다.")
    if result.intent == "financial" and re.search(r"분기|반기|별도", original):
        raise ValueError("현재 실적 조회는 연결 기준 연간 사업보고서만 지원합니다.")
    intent = {"quote": "현재가", "financial": "실적", "news": "관련 뉴스", "composite": "주가 변동 이유와 관련 뉴스"}[result.intent]
    years = " ".join(re.findall(r"(?<!\d)20\d{2}(?!\d)", original))
    return f"{COMPANIES.get(code, selected.name)} {code} {years} {intent}"
