from __future__ import annotations

import re

from app.quote_tool import ALIASES, named_codes, resolve_stock, is_quote_question
from app.financial_tool import is_financial_question
from app.news_tool import is_news_question
from app.composite import is_composite_question
from app.report_rag import is_report_question
from app.disclosure_tool import is_disclosure_question


# '왜 적자야?', '영업이익은 왜 줄었어?'처럼 기업·연도 없이 이유만 묻는 후속 질문.
REASON_FOLLOWUP = re.compile(
    r"^\s*(?:그럼|그러면|그렇다면|근데|그런데)?\s*(?:(?:당기순이익|영업이익|순이익|매출액?|영업손실)(?:이|은|는|도)?\s*)?(?:왜|이유|원인|어째서|적자|흑자)")


def prepare_question(message, previous, selected):
    last = next((item.content for item in reversed(previous) if item.role == "user"), None)
    codes = named_codes(message)
    has_intent = (is_quote_question(message) or is_financial_question(message) or is_news_question(message)
                  or is_composite_question(message) or is_report_question(message) or is_disclosure_question(message))
    # 기업명만 바꾼 짧은 후속 질문에만 직전 조회 종류를 적용한다.
    brief = re.fullmatch(r"(?:그럼|그러면|그렇다면)?\s*([가-힣A-Za-z0-9 ]+?)(?:은|는|도)?\s*[?!.]*", message)
    if not has_intent and brief and (codes or message.startswith(("그럼", "그러면", "그렇다면"))):
        if not codes:
            raise ValueError("어느 기업을 말씀하시는지 확인해 주세요. 기업명이나 종목 코드 6자리를 알려 주세요.")
        if not last or not (is_quote_question(last) or is_financial_question(last) or is_news_question(last)
                            or is_composite_question(last) or is_report_question(last) or is_disclosure_question(last)):
            raise ValueError("해당 기업의 현재가·실적·뉴스·공시 중 어떤 정보를 확인할까요?")
        stock = resolve_stock(message, selected)
        cleaned = last
        for alias in sorted(ALIASES, key=len, reverse=True):
            cleaned = re.sub(re.escape(alias), "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"(?<!\d)\d{6}(?!\d)", "", cleaned)
        return f"{stock.name} {stock.symbol} {cleaned}", stock
    if has_intent:
        resolve_stock(message, selected)  # Tool 연결 여부와 무관하게 명시 기업을 확인한다.
    if has_intent and not codes and last and is_financial_question(message) and REASON_FOLLOWUP.match(message):
        # 직전 질문의 기업과 사업연도를 이어받는다(사업보고서 RAG 도 같은 연도 보고서를 쓴다).
        stock = resolve_stock(last, selected)
        years = re.findall(r"(?<!\d)20\d{2}(?!\d)", last) if is_financial_question(last) and not re.search(r"(?<!\d)20\d{2}(?!\d)", message) else []
        return f"{stock.name} {stock.symbol} {message} {' '.join(years[-1:])}".strip(), stock
    if has_intent and not codes and last:
        # ‘그 회사 실적은?’처럼 지시어가 있거나 기업 없이 조회 종류를 바꾼 경우.
        stock = resolve_stock(message, selected)  # 모르는 명시 기업을 먼저 거부한다.
        if re.match(r"^(?:그럼\s*)?(?:그\s*회사|해당\s*기업|실적|뉴스|현재가|주가|매출|영업이익|순이익|관련\s*기사|공시)", message):
            stock = resolve_stock(last, selected)
            return (message if stock.symbol == selected.symbol else f"{stock.name} {stock.symbol} {message}"), stock
    return message, selected
