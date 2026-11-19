from __future__ import annotations

from datetime import datetime
from decimal import Decimal, InvalidOperation
import re
from zoneinfo import ZoneInfo

import httpx

from app.schemas import StockContext


def is_financial_question(message: str) -> bool:
    compact = message.replace(" ", "")
    return any(word in compact for word in ("실적", "매출", "영업이익", "순이익", "적자", "흑자", "영업손실", "순손실")) and not any(
        word in compact for word in ("뭐야", "뜻", "정의", "계산법", "계산방법")
    )


def financial_year(message: str) -> int | None:
    if any(word in message for word in ("분기", "반기", "별도")):
        raise ValueError("현재 실적 조회는 연결 기준 연간 사업보고서만 지원합니다. 기업과 사업연도를 지정해 질문해 주세요.")
    years = set(re.findall(r"(?<!\d)(20\d{2})(?!\d)", message))
    if len(years) > 1:
        raise ValueError("한 번에 하나의 사업연도를 지정해 주세요. 해당 보고서의 전년 대비 실적은 함께 확인할 수 있습니다.")
    year = int(next(iter(years))) if years else None
    current = datetime.now(ZoneInfo("Asia/Seoul")).year
    if year is not None and not 2015 <= year < current:
        raise ValueError("연간 실적은 2015년부터 전년도까지 조회할 수 있습니다.")
    return year


def financial_years(message: str) -> list[int | None]:
    if any(word in message for word in ("분기", "반기", "별도")):
        raise ValueError("현재 실적 조회는 연결 기준 연간 사업보고서만 지원합니다.")
    current = datetime.now(ZoneInfo("Asia/Seoul")).year
    years = sorted({int(y) for y in re.findall(r"(?<!\d)(20\d{2})(?!\d)", message)})
    recent = re.search(r"최근\s*(\d+)\s*(?:개\s*)?년", message)
    if recent:
        count = int(recent.group(1))
        if not 1 <= count <= 3:
            raise ValueError("한 번에 최대 3개 사업연도를 비교할 수 있습니다.")
        end = years[-1] if years else current - 1
        years = list(range(end - count + 1, end + 1))
    elif len(years) == 2 and re.search(r"~|부터|까지|–|20\d{2}\s*(?:년)?\s*-", message):
        if years[-1] - years[0] > 2:
            raise ValueError("한 번에 최대 3개 사업연도를 비교할 수 있습니다.")
        years = list(range(years[0], years[-1] + 1))
    if len(years) > 3:
        raise ValueError("한 번에 최대 3개 사업연도를 비교할 수 있습니다.")
    if any(not 2015 <= year < current for year in years):
        raise ValueError("연간 실적은 2015년부터 전년도까지 조회할 수 있습니다.")
    return years or [None]


class FinancialResultTool:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    async def get_financial_result(self, stock: StockContext, year: int | None) -> dict:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=10, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/financials", params={} if year is None else {"year": year})
            response.raise_for_status()
            report = response.json()
        if report["stockCode"] != stock.symbol or report["fsDiv"] != "CFS" or report["reportCode"] != "11011":
            raise ValueError("실적 기준이 일치하지 않습니다.")
        if year is not None and report["businessYear"] != year:
            raise ValueError("사업연도가 일치하지 않습니다.")
        if report["currency"] != "KRW":
            raise ValueError("지원하지 않는 통화입니다.")
        metrics = []
        for item in report["metrics"]:
            available = item["status"] == "AVAILABLE"
            metrics.append({
                "label": item["label"], "status": item["status"],
                "current": self._amount(item.get("currentAmount")) if available else None,
                "previous": self._amount(item.get("previousAmount")) if available else None,
                "growthRate": item.get("growthRate") if available else None,
                "comparison": item.get("comparison") if available else "UNAVAILABLE",
            })
        if not any(item["current"] is not None for item in metrics):
            raise ValueError("확인 가능한 실적이 없습니다.")
        return {
            "tool": "get_financial_result", "name": stock.name, "symbol": stock.symbol,
            "businessYear": report["businessYear"], "previousYear": report["previousYear"],
            "basis": "연결 · 연간 사업보고서", "unit": "억 원", "metrics": metrics,
            "sourceUrl": report.get("sourceUrl"),
        }

    @staticmethod
    def _amount(value: str | None) -> str | None:
        if value is None:
            return None
        try:
            amount = Decimal(value)
            if not amount.is_finite():
                raise ValueError("유효하지 않은 실적 수치입니다.")
            return format(amount / Decimal("100000000"), "f")
        except InvalidOperation as error:
            raise ValueError("유효하지 않은 실적 수치입니다.") from error
