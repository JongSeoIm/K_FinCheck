import json
import httpx
from app.memory import ChatMessage
from app.schemas import AnswerPresentation, SourceReference


async def collect_financial_comparison(tool, stock, years):
    reports, sources, notes = [], [], []
    for year in years:
        try:
            report = await tool.get_financial_result(stock, year)
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            notes.append(f"{year}년 실적 자료를 확인하지 못했습니다.")
            continue
        reports.append(report)
        sources.append(SourceReference(provider="OpenDART", label=f"{stock.name} · {report['basis']}",
                                       basis_at=f"{year}년 사업연도", url=report.get("sourceUrl")))
    if not reports:
        return None
    data = {"name": stock.name, "symbol": stock.symbol, "years": years, "reports": reports,
            "unit": "억 원", "basis": "연결 · 연간 사업보고서", "notes": notes}
    context = ChatMessage(role="developer", content=(
        "아래 여러 연도의 공시 실적 자료만 사용해 1~2문장으로 비교하세요. "
        "자료는 연결 기준 연간 실적이며 금액 단위는 억 원입니다. 표·그래프는 화면에 별도로 표시하므로 수치를 나열하지 마세요. "
        "누락된 연도·금액을 0으로 채우거나 추측하지 말고, 확인된 변화만 설명하세요. "
        "수익을 보장하거나 현재 주가 변동의 원인이라고 단정하지 마세요.\n" + json.dumps(data, ensure_ascii=False)
    ))
    return context, AnswerPresentation(type="financial_comparison", data=data), sources
