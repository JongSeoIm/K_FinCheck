"""조회 Tool 4개. 실패하면 값을 만들지 않고 ok=False 와 이유를 돌려줍니다."""

from .schemas import Source, ToolResult
from .spring_client import SpringClient, SpringError


class CompanyError(Exception):
    pass


async def resolve_company(spring: SpringClient, company: str) -> dict:
    """기업명 또는 종목 코드를 지원 종목 하나로 정합니다."""
    query = company.strip()
    matches = await spring.companies(query)
    exact = [m for m in matches if query in (m["stockCode"], m["companyName"])]
    if len(exact) == 1:
        return exact[0]
    if len(matches) == 1:
        return matches[0]
    if not matches:
        raise CompanyError(f"지원하지 않는 기업입니다: {query}")
    names = ", ".join(m["companyName"] for m in matches[:5])
    raise CompanyError(f"기업을 하나로 정할 수 없습니다. 후보: {names}")


async def get_company_info(spring: SpringClient, company: str) -> ToolResult:
    tool = "get_company_info"
    try:
        data = await resolve_company(spring, company)
    except (SpringError, CompanyError) as error:
        return ToolResult(tool=tool, ok=False, error=str(error))
    return ToolResult(tool=tool, ok=True, data=data)


async def get_stock_quote(spring: SpringClient, company: str) -> ToolResult:
    tool = "get_stock_quote"
    try:
        target = await resolve_company(spring, company)
        data = await spring.quote(target["stockCode"])
    except (SpringError, CompanyError) as error:
        return ToolResult(tool=tool, ok=False, error=str(error))
    source = Source(provider=data["provider"], label=f'{data["companyName"]} 현재가', basisAt=data.get("basisAt"))
    return ToolResult(tool=tool, ok=True, data=data, sources=[source])


async def get_financial_result(spring: SpringClient, company: str, year: int | None = None) -> ToolResult:
    tool = "get_financial_result"
    try:
        target = await resolve_company(spring, company)
        data = await spring.financial(target["stockCode"], year)
    except (SpringError, CompanyError) as error:
        return ToolResult(tool=tool, ok=False, error=str(error))
    label = f'{data["companyName"]} {data["businessYear"]}년 {data["reportName"]} ({data["basis"]})'
    source = Source(provider="OpenDART", label=label, basisAt=str(data["businessYear"]), url=data.get("url"))
    return ToolResult(tool=tool, ok=True, data=data, sources=[source])


async def get_company_news(spring: SpringClient, company: str) -> ToolResult:
    tool = "get_company_news"
    try:
        target = await resolve_company(spring, company)
        data = await spring.news(target["stockCode"])
    except (SpringError, CompanyError) as error:
        return ToolResult(tool=tool, ok=False, error=str(error))
    sources = [
        Source(provider=data["provider"], label=a["title"], basisAt=a.get("datetime"), url=a.get("url"))
        for a in data["articles"]
    ]
    return ToolResult(tool=tool, ok=True, data=data, sources=sources)