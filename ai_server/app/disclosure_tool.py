"""공시 목록 조회 Tool (get_company_disclosures).

Spring 이 종목 코드·기간을 미리 만든 SQL 에 넣어 DB(disclosure)에서 읽는다.
아직 저장하지 않은 날짜는 Spring 이 OpenDART 에서 받아 저장한 뒤 읽는다.
"""
from __future__ import annotations

import re

import httpx

from app.market_date import DisclosurePeriod
from app.schemas import StockContext


DART_URL = "https://dart.fss.or.kr/"
MAX_ITEMS_FOR_AI = 20


def is_disclosure_question(message: str) -> bool:
    """'어제 삼성전자 공시', '최근 공시 뭐 있어?' 처럼 공시 목록을 묻는지.
    사업보고서 내용(RAG)·실적 숫자(Tool)·공시라는 용어 뜻은 여기서 다루지 않는다."""
    compact = message.casefold().replace(" ", "")
    if "공시" not in compact or "사업보고서" in compact:
        return False
    if re.search(r"공시(?:란|이란|가뭐야|가무엇|의뜻|뜻|의정의|정의)", compact):
        return False
    return not any(word in compact for word in ("실적", "매출", "영업이익", "순이익"))


class CompanyDisclosureTool:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    async def get_company_disclosures(self, stock: StockContext, period: DisclosurePeriod) -> dict:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=20, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/disclosures",
                                        params={"from": period.start.isoformat(), "to": period.end.isoformat()})
            response.raise_for_status()
            body = response.json()
        if body.get("stockCode") != stock.symbol or not isinstance(body.get("items"), list):
            raise ValueError("요청한 종목의 공시 목록이 아닙니다.")
        items = []
        for item in body["items"]:
            url = str(item.get("sourceUrl") or "")
            title = str(item.get("reportName") or "").strip()
            if not title or not url.startswith(DART_URL):
                continue
            items.append({"date": item.get("receivedDate"), "title": title[:300], "filer": item.get("filerName"),
                          "remark": item.get("remark"), "url": url})
        return {
            "tool": "get_company_disclosures", "symbol": stock.symbol, "name": body.get("companyName") or stock.name,
            "from": period.start.isoformat(), "to": period.end.isoformat(), "label": period.label,
            "items": items, "truncated": bool(body.get("truncated")),
            "basis": "OpenDART 공시 목록 (서버 DB 저장본)",
        }


def for_ai(data: dict) -> dict:
    """GPT 에 넣을 때는 최근 20건의 날짜·제목·제출인만 넣는다(토큰 절약). 화면에는 전체를 보여 준다."""
    return {"name": data["name"], "symbol": data["symbol"], "period": data["label"], "count": len(data["items"]),
            "truncated": data["truncated"] or len(data["items"]) > MAX_ITEMS_FOR_AI,
            "items": [{"date": item["date"], "title": item["title"], "filer": item["filer"]}
                      for item in data["items"][:MAX_ITEMS_FOR_AI]]}
