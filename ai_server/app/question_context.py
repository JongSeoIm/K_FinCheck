from __future__ import annotations

import re

from app.quote_tool import ALIASES, named_codes, resolve_stock, is_quote_question
from app.financial_tool import is_financial_question
from app.policy_tool import is_policy_question
from app.composite import is_composite_question


def prepare_question(message, previous, selected):
    last = next((item.content for item in reversed(previous) if item.role == "user"), None)
    codes = named_codes(message)
    has_intent = is_quote_question(message) or is_financial_question(message) or is_policy_question(message) or is_composite_question(message)
    # 기업명만 바꾼 짧은 후속 질문에만 직전 조회 종류를 적용한다.
    brief = re.fullmatch(r"(?:그럼|그러면|그렇다면)?\s*([가-힣A-Za-z0-9 ]+?)(?:은|는|도)?\s*[?!.]*", message)
    if not has_intent and brief and (codes or message.startswith(("그럼", "그러면", "그렇다면"))):
        if not codes:
            raise ValueError("어느 기업을 말씀하시는지 확인해 주세요. 기업명이나 종목 코드 6자리를 알려 주세요.")
        if not last or not (is_quote_question(last) or is_financial_question(last) or is_policy_question(last) or is_composite_question(last)):
            raise ValueError("해당 기업의 현재가·실적·산업 정책 중 어떤 정보를 확인할까요?")
        stock = resolve_stock(message, selected)
        cleaned = last
        for alias in sorted(ALIASES, key=len, reverse=True):
            cleaned = re.sub(re.escape(alias), "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"(?<!\d)\d{6}(?!\d)", "", cleaned)
        return f"{stock.name} {stock.symbol} {cleaned}", stock
    if has_intent:
        resolve_stock(message, selected)  # Tool 연결 여부와 무관하게 명시 기업을 확인한다.
    if has_intent and not codes and last:
        # ‘그 회사 실적은?’처럼 지시어가 있거나 기업 없이 조회 종류를 바꾼 경우.
        stock = resolve_stock(message, selected)  # 모르는 명시 기업을 먼저 거부한다.
        if re.match(r"^(?:그럼\s*)?(?:그\s*회사|해당\s*기업|실적|정책|규제|현재가|주가|매출|영업이익|순이익|산업\s*동향)", message):
            stock = resolve_stock(last, selected)
            return (message if stock.symbol == selected.symbol else f"{stock.name} {stock.symbol} {message}"), stock
    return message, selected
