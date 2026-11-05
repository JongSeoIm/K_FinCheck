"""선택한 Tool 을 함께 실행하고, 결과를 LLM 에게 줄 문장으로 정리합니다."""

import asyncio

from .company_tool import get_company_info
from .financial_tool import get_financial_result
from .formatters import eok, kst_text, rate, won
from .news_tool import get_company_news
from .quote_tool import get_stock_quote
from .schemas import ToolResult
from .spring_client import SpringClient

TOOL_LABELS = {
    "get_company_info": "기업 정보",
    "get_stock_quote": "현재가",
    "get_financial_result": "실적",
    "get_company_news": "기사",
}
COMPARISON_TEXT = {"TURN_PROFIT": "흑자 전환", "TURN_LOSS": "적자 전환", "LOSS_CONTINUE": "적자 지속", "NOT_COMPARABLE": "전년 비교 불가"}
UNAVAILABLE_TEXT = {"AMBIGUOUS": "값 확인 필요", "UNSUPPORTED_CURRENCY": "원화 외 통화"}


async def run_tool(spring: SpringClient, name: str, company: str, year: int | None) -> ToolResult:
    if name == "get_stock_quote":
        return await get_stock_quote(spring, company)
    if name == "get_financial_result":
        return await get_financial_result(spring, company, year)
    if name == "get_company_news":
        return await get_company_news(spring, company)
    return await get_company_info(spring, company)


async def run_tools(spring: SpringClient, tools: list[str], companies: list[str], year: int | None) -> list[ToolResult]:
    calls = [run_tool(spring, tool, company, year) for company in companies for tool in tools]
    return list(await asyncio.gather(*calls))


def quote_facts(data: dict) -> str:
    basis = kst_text(data.get("basisAt")) or "확인 불가"
    state = "장중 실시간 체결 기준" if data.get("dataSource") == "realtime" else "KIS 현재가 조회 기준"
    closed = "" if data.get("marketOpen") else " 정규장 시간이 아니므로 시간외 거래가 반영된 값일 수 있습니다."
    return (
        f'{data["companyName"]} 현재가 {won(data["price"])}, 전일 대비 {int(data["priceChange"]):+,}원({rate(data["changeRate"])}), '
        f'거래량 {int(data["volume"]):,}주. {state}, {data.get("basisType", "기준 시각")} {basis}.{closed}'
    )


def financial_facts(data: dict) -> str:
    lines = [f'{data["companyName"]} {data["businessYear"]}년 {data["reportName"]} ({data["basis"]} 기준, 단위 억 원)']
    if data.get("stale"):
        lines.append("최신 자료를 받지 못해 저장된 자료입니다.")
    for item in data["items"]:
        status = UNAVAILABLE_TEXT.get(item.get("valueStatus"))
        if status:
            lines.append(f'{item["accountName"]}: {status}')
            continue
        change = f'전년 대비 {float(item["changeRate"]):+.1f}%' if item.get("changeRate") is not None else COMPARISON_TEXT.get(item.get("comparison"), "전년 비교 불가")
        lines.append(f'{item["accountName"]}: 당기 {eok(item.get("currentAmount"))}, 전기 {eok(item.get("previousAmount"))}, {change}')
    return "\n".join(lines)


def news_facts(data: dict) -> str:
    lines = [f'{data["companyName"]} 관련 기사 (제목과 검색 결과 설명만 확보)']
    for article in data["articles"][:5]:
        published = kst_text(article.get("datetime")) or "시각 확인 불가"
        lines.append(f'- {published} | {article["title"]} | {article.get("description", "")}')
    return "\n".join(lines)


def facts_of(result: ToolResult) -> str:
    if result.tool == "get_stock_quote":
        return quote_facts(result.data)
    if result.tool == "get_financial_result":
        return financial_facts(result.data)
    if result.tool == "get_company_news":
        return news_facts(result.data)
    data = result.data
    industry = f', 업종 {data["industry"]}' if data.get("industry") else ""
    return f'{data["companyName"]} 종목 코드 {data["stockCode"]}, 시장 {data["market"]}{industry}'


def missing_text(results: list[ToolResult]) -> str:
    failed = [f'{TOOL_LABELS.get(r.tool, r.tool)}: {r.error}' for r in results if not r.ok]
    return " / ".join(failed)
