"""get_company_info: 기업명 또는 종목 코드를 지원 종목 하나로 정합니다."""

from .schemas import ToolResult
from .spring_client import SpringClient, SpringError


class CompanyError(Exception):
    pass


async def resolve_company(spring: SpringClient, company: str) -> dict:
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
