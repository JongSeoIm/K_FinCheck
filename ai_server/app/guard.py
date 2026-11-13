from __future__ import annotations


FINANCE_TERMS = {
    "주식", "주가", "종목", "현재가", "시세", "체결", "등락", "상승", "하락",
    "오른", "올랐", "내린", "내렸", "가격", "거래량", "매수", "매도", "투자",
    "재무", "실적", "매출", "영업이익", "순이익", "자산", "부채", "자본",
    "부채비율", "공시", "사업보고서", "dart", "배당", "per", "pbr", "roe",
    "정책", "산업", "규제", "지원책", "보조금", "세액공제", "관세", "수출통제",
    "증권", "코스피", "코스닥", "금리", "환율", "채권", "etf",
}


def is_finance_question(message: str, stock_name: str, stock_symbol: str) -> bool:
    normalized = message.casefold().replace(" ", "")
    stock_tokens = {stock_name.casefold().replace(" ", ""), stock_symbol}
    return any(term in normalized for term in FINANCE_TERMS) or any(
        token and token in normalized for token in stock_tokens
    )
