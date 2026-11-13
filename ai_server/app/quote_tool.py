from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
import re

import httpx

from app.market_date import DateBasis, strip_date_expressions
from app.schemas import StockContext


FALLBACK_DAYS = 7  # 그날 일봉이 없을 때 Spring 이 거슬러 찾는 최대 일수와 같다


COMPANIES = {
    "005930": "삼성전자", "000660": "SK하이닉스", "035420": "NAVER",
    "035720": "카카오", "005380": "현대차", "000270": "기아",
    "051910": "LG화학", "006400": "삼성SDI",
}
ALIASES = {name.casefold(): code for code, name in COMPANIES.items()}
ALIASES.update({"네이버": "035420", "하이닉스": "000660", "현대자동차": "005380", "엘지화학": "051910"})
ALIASES.update({"삼전": "005930", "하닉": "000660"})


def named_codes(message: str) -> set[str]:
    compact = message.casefold().replace(" ", "")
    return set(re.findall(r"(?<!\d)\d{6}(?!\d)", message)) | {
        code for name, code in ALIASES.items() if name in compact
    }


def is_quote_question(message: str) -> bool:
    compact = message.replace(" ", "")
    return any(word in compact for word in ("현재가", "주가", "시세", "등락", "거래량", "얼마")) and not any(
        word in compact for word in ("뭐야", "뜻", "정의", "계산방법", "계산법")
    )


def resolve_stock(message: str, selected: StockContext) -> StockContext:
    compact = message.casefold().replace(" ", "")
    codes = named_codes(message)
    if len(codes) > 1:
        raise ValueError("한 번에 한 종목씩 질문해 주세요.")
    if not codes and selected.name.casefold().replace(" ", "") not in compact:
        company_text = re.sub(r"(?:최근\s*)?\d+\s*(?:개\s*)?년(?:부터|까지)?", "", strip_date_expressions(message))
        company_text = re.sub(r"^\s*(?:과|와|~|–|-)\s*", "", company_text)
        candidate = re.search(r"([가-힣A-Za-z]+?)(?:의)?\s*(?:(?:최근|관련|연간|연결|최신)\s*)*(?:현재가|주가|시세|실적|매출|영업이익|순이익|뉴스|기사|공시)", company_text)
        if candidate and candidate.group(1) not in {"최근", "현재", "오늘", "최신", "연간", "관련", "기업", "회사", "그회사", "해당기업", "연결", "별도", "분기", "반기",
                                                 "왜", "그럼", "그러면", "그렇다면", "근데", "그런데", "그리고", "또", "어째서", "이번", "작년", "올해", "전년"}:
            raise ValueError("기업명을 확인할 수 없습니다. 지원 기업명이나 국내 종목 코드 6자리를 알려 주세요.")
    code = next(iter(codes), selected.symbol)
    return StockContext(symbol=code, name=COMPANIES.get(code, selected.name if code == selected.symbol else code))


class StockQuoteTool:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    async def get_stock_quote(self, stock: StockContext, basis: DateBasis | None = None) -> dict:
        if basis is not None and not basis.live:
            return await self._daily_close(stock, basis)
        async with httpx.AsyncClient(base_url=self._base_url, timeout=5, transport=self._transport) as client:
            try:
                snapshot = await client.get("/api/realtime/snapshot")
                snapshot.raise_for_status()
                quote = snapshot.json().get("quotes", {}).get(stock.symbol)
                if quote:
                    received = datetime.fromisoformat(quote["receivedAt"].replace("Z", "+00:00"))
                    age = (datetime.now(timezone.utc) - received).total_seconds()
                    if 0 <= age <= 15:
                        return self._result(stock, quote, quote["exchangeTime"], "체결 시각", "실시간")
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                pass
            response = await client.get(f"/api/stocks/{stock.symbol}/price")
            response.raise_for_status()
            return self._result(stock, response.json(), datetime.now(timezone.utc).isoformat(), "조회 시각", "REST")

    async def _daily_close(self, stock: StockContext, basis: DateBasis) -> dict:
        """과거 날짜·장 시작 전은 DB(stock_daily_price)에서 그날 종가를 조회한다.
        Spring 이 종목 코드·날짜를 미리 만든 SQL 에 넣어 읽고, DB 에 없는 날만 KIS 에서 받아 저장한 뒤 읽는다.
        그날 일봉이 KIS 에도 없으면(휴장일 목록에 없는 임시공휴일 등) Spring 이 7일 안의 직전 거래일 일봉을 준다.
        그때는 tradeDate·fallbackFrom 을 붙여 호출하는 쪽이 "직전 거래일 기준"이라고 안내하게 한다.
        근처에 일봉이 없으면 만들지 않고 실패로 처리한다."""
        target = basis.end.isoformat()
        async with httpx.AsyncClient(base_url=self._base_url, timeout=8, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/daily-price", params={"date": target})
            if response.status_code == 404:
                raise ValueError("해당 거래일의 일봉이 없습니다.")
            response.raise_for_status()
            day = response.json()
        trade_date = str(day.get("tradeDate") or "")
        try:
            actual = date.fromisoformat(trade_date)
        except ValueError:
            raise ValueError("일봉 날짜가 올바르지 않습니다.") from None
        if day.get("stockCode") != stock.symbol or not basis.end - timedelta(days=FALLBACK_DAYS) <= actual <= basis.end:
            raise ValueError("요청한 종목·날짜의 일봉이 아닙니다.")
        close = day.get("close")
        if not isinstance(close, (int, float)) or close <= 0:
            raise ValueError("유효한 종가가 없습니다.")
        previous = day.get("previousClose")
        change = close - previous if isinstance(previous, (int, float)) and previous > 0 else None
        rate = round(change / previous * 100, 2) if change is not None else None
        return {
            "tool": "get_stock_quote", "symbol": stock.symbol, "name": stock.name,
            "price": close, "priceChange": change, "changeRate": rate, "volume": day.get("volume"),
            "open": day.get("open"), "high": day.get("high"), "low": day.get("low"),
            "currency": "KRW", "basis_at": f"{trade_date} 종가", "time_kind": "일봉 종가", "method": "DB 일봉",
            "tradeDate": trade_date, "fallbackFrom": target if trade_date != target else None,
        }

    @staticmethod
    def _result(stock: StockContext, quote: dict, basis: str, time_kind: str, method: str) -> dict:
        if quote.get("stockCode") != stock.symbol or not isinstance(quote.get("price"), (int, float)) or quote["price"] <= 0:
            raise ValueError("유효한 현재가가 없습니다.")
        return {
            "tool": "get_stock_quote", "symbol": stock.symbol, "name": stock.name,
            "price": quote["price"], "priceChange": quote.get("priceChange"),
            "changeRate": quote.get("changeRate"), "volume": quote.get("volume"),
            "currency": "KRW", "basis_at": basis, "time_kind": time_kind, "method": method,
        }
