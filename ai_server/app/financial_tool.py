"""get_financial_result: Spring 이 저장한 OpenDART 연간 실적을 조회합니다."""

from .company_tool import CompanyError, resolve_company
from .schemas import Source, ToolResult
from .spring_client import SpringClient, SpringError


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
