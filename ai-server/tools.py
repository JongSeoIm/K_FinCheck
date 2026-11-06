"""조회 Tool 4개 (RAG 사용 X)

모든 Tool 은 우리 Spring 서버를 호출한다. 외부 API 키는 Spring 에만 있다.

| Tool                  | 조회 내용                         | 실제 데이터 위치                               |
| get_stock_quote       | 현재가, 등락률, 거래량, 체결 시각 | Spring QuoteStore 우선 → 없으면 KIS REST       |
| get_financial_result  | 매출액, 영업이익, 재무 지표       | Spring → MariaDB (없으면 OpenDART 조회 후 저장) |
| get_company_news      | 관련 기사 제목, 게시 시각, 링크   | Spring → NAVER API HUB                         |
| get_company_info      | 기업명, 종목 코드, 시장 구분      | Spring → MariaDB                               |

Tool 호출 제한 시간은 팀 결정대로 5초. 실패한 Tool 은 ok=False 로 돌려주고, 같은 값을 LLM 지식으로 대신하지 않는다.
"""

import asyncio
import time
from dataclasses import dataclass
from typing import Any

import httpx

import config
from classifier import FINANCIAL_TOOL, INFO_TOOL, NEWS_TOOL, QUOTE_TOOL, Company, ToolCall

NEWS_COUNT = 5  # 답변 근거로 넘기는 기사 수
# 기사 제목에서 종목을 찾는 줄임말 (Spring NewsService.ALIASES 와 같은 값)
NEWS_ALIASES = {
    "005930": ["삼전", "만전자"], "000660": ["하이닉스", "하닉", "삼전닉스", "만닉스"],
    "005380": ["현대자동차"], "035420": ["네이버"], "000270": ["기아차"], "051910": ["엘지화학"], "006400": ["삼성에스디아이"],
}


@dataclass
class ToolResult:
    name: str
    symbol: str
    ok: bool
    data: Any = None
    error: str = ""
    no_data: bool = False      # 조회는 됐지만 자료가 없음 (장애가 아니다)


class ToolError(Exception):
    def __init__(self, message: str, no_data: bool = False):
        super().__init__(message)
        self.no_data = no_data


async def _get(client: httpx.AsyncClient, path: str, params: dict | None = None) -> dict:
    try:
        res = await client.get(f"{config.SPRING_BASE_URL}{path}", params=params)
    except httpx.TimeoutException as e:
        raise ToolError("조회 시간이 초과되었습니다.") from e
    except httpx.HTTPError as e:
        raise ToolError("Spring 서버에 연결할 수 없습니다.") from e
    try:
        body = res.json()
    except ValueError:
        body = {}
    if res.status_code >= 400:
        raise ToolError(body.get("error") or f"조회 실패 ({res.status_code})", no_data=res.status_code == 404)
    return body


async def get_stock_quote(client: httpx.AsyncClient, call: ToolCall) -> dict:
    quote = (await _get(client, f"/api/stocks/{call.symbol}/quote")).get("quote")
    if not quote:
        raise ToolError("시세가 없습니다.")
    return quote


async def get_financial_result(client: httpx.AsyncClient, call: ToolCall) -> dict:
    params = {k: v for k, v in {"year": call.year, "report": call.report}.items() if v}
    financial = (await _get(client, f"/api/companies/{call.symbol}/financials", params)).get("financial")
    if not financial:
        raise ToolError("실적이 없습니다.")
    return financial


async def get_company_news(client: httpx.AsyncClient, call: ToolCall) -> list[dict]:
    news = (await _get(client, f"/api/stocks/{call.symbol}/news")).get("news") or []
    related = [n for n in news if n.get("related")]
    if not related:
        raise ToolError(f"{call.company or call.symbol}의 관련 기사를 찾지 못했습니다.", no_data=True)
    # 제목에 이 기업이 나온 기사를 먼저, 요약에만 나온 기사(시황 등)를 나중에 고른다
    words = ([call.company] if call.company else []) + NEWS_ALIASES.get(call.symbol, [])
    related.sort(key=lambda n: not any(w in (n.get("headline") or "") for w in words))
    return related[:NEWS_COUNT]


async def get_company_info(client: httpx.AsyncClient, call: ToolCall) -> dict:
    company = (await _get(client, f"/api/companies/{call.symbol}")).get("company")
    if not company:
        raise ToolError("기업 정보가 없습니다.")
    return company


TOOLS = {
    QUOTE_TOOL: get_stock_quote,
    FINANCIAL_TOOL: get_financial_result,
    NEWS_TOOL: get_company_news,
    INFO_TOOL: get_company_info,
}


async def _run_one(client: httpx.AsyncClient, call: ToolCall) -> ToolResult:
    try:
        data = await asyncio.wait_for(TOOLS[call.name](client, call), timeout=config.TOOL_TIMEOUT_SECONDS)
        return ToolResult(call.name, call.symbol, True, data)
    except asyncio.TimeoutError:
        return ToolResult(call.name, call.symbol, False, error=f"{config.TOOL_TIMEOUT_SECONDS:g}초 안에 응답이 없습니다.")
    except ToolError as e:
        return ToolResult(call.name, call.symbol, False, error=str(e), no_data=e.no_data)
    except Exception as e:  # 예상하지 못한 오류도 해당 Tool 만 실패로 처리
        return ToolResult(call.name, call.symbol, False, error=f"조회 중 오류 ({type(e).__name__})")


async def run_tools(calls: list[ToolCall]) -> list[ToolResult]:
    """복합 질문은 여러 Tool 을 동시에 호출한다"""
    if not calls:
        return []
    async with httpx.AsyncClient(timeout=config.TOOL_TIMEOUT_SECONDS) as client:
        return list(await asyncio.gather(*(_run_one(client, call) for call in calls)))


# ===== 지원 종목 목록 (질문에서 기업명을 찾기 위해, 10분 저장) =====
_companies_cache: tuple[float, list[Company]] | None = None


async def list_companies() -> list[Company]:
    global _companies_cache
    if _companies_cache and time.time() - _companies_cache[0] < 600:
        return _companies_cache[1]
    async with httpx.AsyncClient(timeout=config.TOOL_TIMEOUT_SECONDS) as client:
        body = await _get(client, "/api/stocks", {"query": ""})
    companies = [Company(s["symbol"], s["name"], s.get("market", "")) for s in body.get("list", [])]
    if companies:
        _companies_cache = (time.time(), companies)
    return companies
