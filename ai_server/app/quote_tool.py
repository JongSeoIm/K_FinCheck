"""get_stock_quote: Spring 의 실시간 시세를 먼저 쓰고, 없거나 장외이면 KIS REST 현재가를 씁니다."""

from .company_tool import CompanyError, resolve_company
from .schemas import Source, ToolResult
from .spring_client import SpringClient, SpringError


async def get_stock_quote(spring: SpringClient, company: str) -> ToolResult:
    tool = "get_stock_quote"
    try:
        target = await resolve_company(spring, company)
        data = await spring.quote(target["stockCode"])
    except (SpringError, CompanyError) as error:
        return ToolResult(tool=tool, ok=False, error=str(error))
    source = Source(provider=data["provider"], label=f'{data["companyName"]} 현재가', basisAt=data.get("basisAt"))
    return ToolResult(tool=tool, ok=True, data=data, sources=[source])
