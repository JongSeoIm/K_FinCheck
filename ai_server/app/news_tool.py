"""get_company_news: 기업 관련 최신 기사의 제목·설명·게시 시각·링크를 조회합니다."""

from .company_tool import CompanyError, resolve_company
from .schemas import Source, ToolResult
from .spring_client import SpringClient, SpringError


async def get_company_news(spring: SpringClient, company: str) -> ToolResult:
    tool = "get_company_news"
    try:
        target = await resolve_company(spring, company)
        data = await spring.news(target["stockCode"])
    except (SpringError, CompanyError) as error:
        return ToolResult(tool=tool, ok=False, error=str(error))
    if not data["articles"]:
        return ToolResult(tool=tool, ok=False, error=f'{data["companyName"]} 관련 기사를 찾지 못했습니다.')
    sources = [
        Source(provider=data["provider"], label=a["title"], basisAt=a.get("datetime"), url=a.get("url"))
        for a in data["articles"]
    ]
    return ToolResult(tool=tool, ok=True, data=data, sources=sources)
