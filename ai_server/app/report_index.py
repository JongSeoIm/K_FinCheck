"""사업보고서 RAG 색인을 미리 만든다. 첫 질문이 느려지지 않게 시연 전에 한 번 실행한다.

사용: (ai_server 폴더에서, Spring 백엔드를 켠 상태로)
    python -m app.report_index                                  # 8종목 최신 보고서
    python -m app.report_index 005930 000660                    # 지정 종목 최신 보고서
    python -m app.report_index 000660 --years 2023 2024 2025    # 지정 연도 보고서
    python -m app.report_index --years 2023 2024 2025           # 8종목 × 지정 연도
    python -m app.report_index --rebuild                        # 이미 만든 색인도 다시 만든다(청크 기준을 바꿨을 때)
    python -m app.report_index --from-sqlite                    # 예전 data/reports.sqlite3 색인을 MariaDB 로 복사
                                                                #  (OpenDART·OpenAI 호출 없음, 비용 0)
"""
from __future__ import annotations

import asyncio
import sys

from app.config import get_settings
from app.main import build_report_rag
from app.report_rag import ReportStore, SpringReportStore
from app.quote_tool import COMPANIES
from app.schemas import StockContext


async def main(codes: list[str], years: list[int | None], rebuild: bool = False) -> None:
    rag = build_report_rag(get_settings())
    for code in codes:
        stock = StockContext(symbol=code, name=COMPANIES.get(code, code))
        for year in years:
            label = "최신" if year is None else f"{year}년"
            try:
                meta = await rag.ensure_indexed(stock, year, rebuild=rebuild)
                print(f"완료: {stock.name}({code}) {label} {meta.get('reportName')} 접수번호 {meta['receiptNo']}")
            except Exception as error:  # 한 종목·연도가 실패해도 나머지는 계속한다.
                print(f"실패: {stock.name}({code}) {label} {type(error).__name__}: {error}")


async def copy_from_sqlite() -> None:
    """예전 SQLite 색인(청크·벡터)을 그대로 MariaDB 에 저장한다. 이미 같은 모델로 있으면 건너뛴다."""
    settings = get_settings()
    source = ReportStore(settings.report_store_path)
    target = SpringReportStore(settings.spring_base_url)
    reports = source.reports()
    if not reports:
        print(f"옮길 색인이 없습니다: {settings.report_store_path}")
        return
    for meta in reports:
        receipt, model = meta["receiptNo"], meta["model"]
        try:
            if await target.indexed(receipt, model):
                print(f"건너뜀(이미 있음): {meta['stockCode']} {meta['reportName']} {receipt}")
                continue
            rows = source.load(receipt)
            vectors = [vector for _, _, vector in rows] if rows and all(vector for _, _, vector in rows) else None
            await target.save(meta, model, [(section, text) for section, text, _ in rows], vectors)
            print(f"복사: {meta['stockCode']} {meta['reportName']} {receipt} 조각 {len(rows)}개")
        except Exception as error:  # 한 건이 실패해도 나머지는 계속한다.
            print(f"실패: {receipt} {type(error).__name__}: {error}")


def parse_args(args: list[str]) -> tuple[list[str], list[int | None]]:
    args = [arg for arg in args if arg != "--rebuild"]
    if "--years" in args:
        index = args.index("--years")
        codes, years = args[:index], [int(y) for y in args[index + 1:]]
    else:
        codes, years = args, [None]
    return codes or list(COMPANIES), years or [None]


if __name__ == "__main__":
    if "--from-sqlite" in sys.argv[1:]:
        asyncio.run(copy_from_sqlite())
    else:
        asyncio.run(main(*parse_args(sys.argv[1:]), rebuild="--rebuild" in sys.argv[1:]))
