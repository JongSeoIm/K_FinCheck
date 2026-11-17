import io
import tempfile
import unittest
import zipfile
from types import SimpleNamespace

import httpx

from app.composite import is_composite_question
from app.rag import OpenAIEmbedder
from app.report_rag import (BusinessReportRag, BusinessReportTool, ReportStore, build_chunks, is_report_question,
                            main_document, parse_sections, select_sections)
from app.schemas import ChatRequest, StockContext
from app.service import ChatService
from tests.test_composite import FinancialTool
from tests.test_service import RecordingProvider

RECEIPT = "20260310000123"

# OpenDART 공시서류원본(document.xml) ZIP 안의 본문 XML 과 같은 구조 (SECTION-n / TITLE / P / TABLE)
REPORT_XML = f"""<?xml version="1.0" encoding="utf-8"?>
<DOCUMENT><DOCUMENT-NAME ACODE="11011">사업보고서</DOCUMENT-NAME><BODY>
<COVER><COVER-TITLE>사 업 보 고 서</COVER-TITLE></COVER>
<SECTION-1><TITLE ATOC="Y" AASSOCNOTE="D-0-1-0-0">I. 회사의 개요</TITLE>
 <SECTION-2><TITLE ATOC="Y">1. 회사의 개요</TITLE><P>당사는 메모리 반도체를 생산합니다.</P></SECTION-2>
</SECTION-1>
<SECTION-1><TITLE ATOC="Y">II. 사업의 내용</TITLE>
 <SECTION-2><TITLE ATOC="Y">1. 사업의 개요</TITLE><P>당사의 주력 제품은 DRAM과 NAND 플래시이며 HBM 매출 비중이 확대되었습니다.</P>
  <TABLE><TBODY><TR><TD>부문</TD><TD>매출 비중</TD></TR><TR><TD>DRAM</TD><TD>77.4%</TD></TR></TBODY></TABLE></SECTION-2>
 <SECTION-2><TITLE ATOC="Y">7. 기타 참고사항</TITLE><P>주요 위험 요인으로 메모리 가격 변동, 환율 변동, 미국 수출 규제가 있습니다.&amp;</P></SECTION-2>
</SECTION-1>
<SECTION-1><TITLE ATOC="Y">III. 재무에 관한 사항</TITLE>
 <SECTION-2><TITLE ATOC="Y">3. 연결재무제표 주석</TITLE><P>{"주석 " * 500}</P></SECTION-2>
 <SECTION-2><TITLE ATOC="Y">6. 배당에 관한 사항</TITLE><P>당사는 잉여현금흐름의 일부를 주주환원에 사용하는 배당 정책을 유지합니다.</P></SECTION-2>
</SECTION-1>
<SECTION-1><TITLE ATOC="Y">IV. 이사의 경영진단 및 분석의견</TITLE>
 <P>HBM 수요 증가와 판매 가격 상승으로 영업이익이 크게 개선되었습니다.</P>
</SECTION-1>
</BODY></DOCUMENT>"""


def report_zip(xml: str = REPORT_XML) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(f"{RECEIPT}.xml", xml.encode("utf-8"))
        archive.writestr(f"{RECEIPT}_00760.xml", "<DOCUMENT>감사보고서</DOCUMENT>")
    return buffer.getvalue()


META = {"stockCode": "000660", "companyName": "SK하이닉스", "corpCode": "00164779", "receiptNo": RECEIPT,
        "reportName": "사업보고서 (2025.12)", "businessYear": 2025, "receivedDate": "2026-03-10",
        "sourceUrl": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={RECEIPT}"}


def receipt_for(year):
    return RECEIPT if year is None else f"{year + 1}0315000999"


def spring(calls):
    """Spring 흉내. ?year= 가 있으면 그 연도 보고서, 2019년은 없음(404)."""
    def handler(request):
        calls.append(request.url.path + ("?" + request.url.query.decode() if request.url.query else ""))
        year = request.url.params.get("year")
        year = int(year) if year else None
        if year == 2019:
            return httpx.Response(404, json={"code": "NO_REPORT", "error": "없음"})
        receipt = receipt_for(year)
        if request.url.path.endswith("/business-report"):
            meta = dict(META, receiptNo=receipt)
            if year is not None:
                meta.update(businessYear=year, reportName=f"사업보고서 ({year}.12)", receivedDate=f"{year + 1}-03-15")
            return httpx.Response(200, json=meta)
        xml = REPORT_XML if year is None else REPORT_XML.replace("HBM 수요 증가와 판매 가격 상승으로 영업이익이 크게 개선되었습니다.",
                                                                  f"{year}년에는 메모리 가격 하락과 재고 증가로 영업손실이 발생했습니다.")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(f"{receipt}.xml", xml.encode("utf-8"))
        return httpx.Response(200, content=buffer.getvalue(), headers={"Content-Type": "application/zip"})
    return httpx.MockTransport(handler)


def request(message, symbol="000660", name="SK하이닉스"):
    return ChatRequest(user_id=1, conversation_id="conv_0001", message=message, stock=StockContext(symbol=symbol, name=name))


class FakeEmbeddings:
    """'위험·배당·HBM' 단어 수로 만든 벡터."""
    def __init__(self):
        self.inputs = 0

    async def create(self, model, input, timeout=None):
        self.inputs += len(input)
        vocab = ("위험", "배당", "HBM", "영업이익", "제품")
        return SimpleNamespace(data=[SimpleNamespace(embedding=[text.count(w) + 0.01 for w in vocab]) for text in input],
                               usage=SimpleNamespace(total_tokens=5 * len(input)))


class ParsingTest(unittest.TestCase):
    def test_main_document_skips_attachments(self):
        self.assertIn("사업의 내용", main_document(report_zip(), RECEIPT))

    def test_sections_have_paths_and_tables_become_text(self):
        sections = parse_sections(REPORT_XML)
        paths = [s.path for s in sections]
        self.assertIn("II. 사업의 내용 > 7. 기타 참고사항", paths)
        overview = next(s for s in sections if s.path == "II. 사업의 내용 > 1. 사업의 개요")
        self.assertIn("DRAM | 77.4%", overview.text)
        risk = next(s for s in sections if s.path.endswith("기타 참고사항"))
        self.assertTrue(risk.text.endswith("&"))  # HTML 엔티티 복원

    def test_notes_are_skipped_and_dividend_is_kept(self):
        paths = [s.path for s in select_sections(parse_sections(REPORT_XML))]
        self.assertFalse(any("주석" in p for p in paths))
        self.assertIn("III. 재무에 관한 사항 > 6. 배당에 관한 사항", paths)
        self.assertIn("IV. 이사의 경영진단 및 분석의견", paths)

    def test_chunks_are_1000_with_200_overlap_and_section_prefix(self):
        from app.report_rag import Section
        chunks = build_chunks([Section("II. 사업의 내용", "가" * 2200)])
        self.assertEqual(3, len(chunks))
        self.assertTrue(chunks[0][1].startswith("[II. 사업의 내용] "))
        bodies = [text.split("] ", 1)[1] for _, text in chunks]
        self.assertEqual([1000, 1000, 600], [len(b) for b in bodies])

    def test_report_question_routing(self):
        self.assertTrue(is_report_question("SK하이닉스 위험 요인이 뭐야?"))
        self.assertTrue(is_report_question("삼성전자 실적 왜 좋아졌어?"))
        self.assertTrue(is_report_question("현대차 배당 정책 알려줘"))
        self.assertFalse(is_report_question("삼성전자 주가"))
        self.assertFalse(is_report_question("삼성전자 2024년 실적"))
        self.assertTrue(is_composite_question("삼성전자 왜 올랐어?"))  # 주가 변동 이유는 기존 복합 질문


class ReportRagServiceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.calls = []
        self.embeddings = FakeEmbeddings()
        embedder = OpenAIEmbedder(SimpleNamespace(embeddings=self.embeddings))
        self.rag = BusinessReportRag(BusinessReportTool("http://spring", spring(self.calls)),
                                     ReportStore(f"{self.folder.name}/reports.sqlite3"), embedder)

    async def asyncTearDown(self):
        self.folder.cleanup()

    async def test_risk_question_uses_report_chunks_as_evidence(self):
        provider = RecordingProvider()
        result = await ChatService(provider, report_rag=self.rag).ask(request("SK하이닉스 위험 요인이 뭐야?"))
        self.assertEqual("report", result.presentation.type)
        self.assertIn("기타 참고사항", result.presentation.data["chunks"][0]["section"])
        self.assertEqual("OpenDART", result.sources[0].provider)
        self.assertIn("rcpNo=" + RECEIPT, result.sources[0].url)
        self.assertEqual("2025년 사업연도 사업보고서(2026-03-10 공시) 기준", result.basis)
        context = provider.histories[0][-1].content
        self.assertIn("미국 수출 규제", context)
        self.assertIn("항목 이름을 밝히세요", context)
        self.assertGreater(result.usage["embedding_tokens"], 0)

    async def test_report_is_indexed_once(self):
        service = ChatService(RecordingProvider(), report_rag=self.rag)
        await service.ask(request("SK하이닉스 위험 요인이 뭐야?"))
        chunk_inputs = self.embeddings.inputs
        await service.ask(request("SK하이닉스 배당 정책 알려줘"))
        self.assertEqual(1, sum(c.startswith("/api/stocks/000660/business-report/document") for c in self.calls))
        self.assertEqual(chunk_inputs + 1, self.embeddings.inputs)  # 두 번째는 질문 1개만 벡터화

    async def test_profit_reason_combines_tool_numbers_and_report(self):
        financial = FinancialTool()
        provider = RecordingProvider()
        result = await ChatService(provider, financial_tool=financial, report_rag=self.rag).ask(request("SK하이닉스 실적 왜 좋아졌어?"))
        self.assertEqual("report", result.presentation.type)
        self.assertEqual([2025], financial.calls)
        self.assertIsNotNone(result.presentation.data["financial"])
        self.assertIn("경영진단", " ".join(c["section"] for c in result.presentation.data["chunks"]))

    async def test_unsupported_number_in_answer_is_replaced(self):
        class Inventing(RecordingProvider):
            async def generate(self, history):
                self.histories.append(history)
                return "보고서에 따르면 HBM 비중은 93.1%입니다."
        result = await ChatService(Inventing(), report_rag=self.rag).ask(request("SK하이닉스 HBM 사업 내용 알려줘"))
        self.assertIn("원문 조각을 아래에서 확인", result.answer)

    async def test_supported_number_is_kept(self):
        class Quoting(RecordingProvider):
            async def generate(self, history):
                self.histories.append(history)
                return "사업보고서의 사업의 개요 항목에 따르면 DRAM 매출 비중은 77.4%입니다."
        result = await ChatService(Quoting(), report_rag=self.rag).ask(request("SK하이닉스 주요 제품이 뭐야?"))
        self.assertIn("77.4%", result.answer)

    async def test_spring_failure_does_not_call_model(self):
        rag = BusinessReportRag(BusinessReportTool("http://spring", httpx.MockTransport(lambda r: httpx.Response(502))),
                                ReportStore(f"{self.folder.name}/other.sqlite3"))
        provider = RecordingProvider()
        result = await ChatService(provider, report_rag=rag).ask(request("SK하이닉스 위험 요인"))
        self.assertIn("불러오지 못했습니다", result.answer)
        self.assertEqual([], provider.histories)

    async def test_without_embedder_uses_lexical_search(self):
        rag = BusinessReportRag(BusinessReportTool("http://spring", spring([])), ReportStore(f"{self.folder.name}/lex.sqlite3"))
        meta, hits = await rag.search(StockContext(symbol="000660", name="SK하이닉스"), "배당 정책")
        self.assertIn("배당", hits[0]["section"])


class ReportYearTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from datetime import datetime
        from app.market_date import KST
        self.folder = tempfile.TemporaryDirectory()
        self.calls = []
        self.rag = BusinessReportRag(BusinessReportTool("http://spring", spring(self.calls)),
                                     ReportStore(f"{self.folder.name}/reports.sqlite3"))
        self.clock = lambda: datetime(2026, 11, 10, 10, 0, tzinfo=KST)

    async def asyncTearDown(self):
        self.folder.cleanup()

    async def test_year_in_question_uses_that_years_report_and_numbers(self):
        financial = FinancialTool()
        provider = RecordingProvider()
        service = ChatService(provider, financial_tool=financial, report_rag=self.rag, clock=self.clock)
        result = await service.ask(request("SK하이닉스 2023년 실적 왜 나빠졌어?"))
        self.assertEqual(2023, result.presentation.data["businessYear"])
        self.assertEqual([2023], financial.calls)  # 실적 숫자도 같은 연도
        self.assertIn("year=2023", " ".join(self.calls))
        self.assertIn("2023년에는 메모리 가격 하락", provider.histories[0][-1].content)
        self.assertNotIn("HBM 수요 증가와 판매 가격 상승", provider.histories[0][-1].content)
        self.assertEqual("2023년 사업연도 사업보고서(2024-03-15 공시) 기준", result.basis)

    async def test_last_year_word_and_no_year_means_latest(self):
        service = ChatService(RecordingProvider(), report_rag=self.rag, clock=self.clock)
        result = await service.ask(request("SK하이닉스 작년 위험 요인이 뭐야?"))
        self.assertEqual(2025, result.presentation.data["businessYear"])
        result = await service.ask(request("SK하이닉스 위험 요인이 뭐야?"))
        self.assertEqual(RECEIPT, result.sources[0].url.rsplit("=", 1)[1])

    async def test_years_are_indexed_separately(self):
        stock = StockContext(symbol="000660", name="SK하이닉스")
        await self.rag.ensure_indexed(stock, 2023)
        await self.rag.ensure_indexed(stock, 2024)
        await self.rag.ensure_indexed(stock, 2023)
        documents = [c for c in self.calls if "/document" in c]
        self.assertEqual(["year=2023", "year=2024"], [c.split("?")[1] for c in documents])

    async def test_missing_year_and_bad_year_do_not_call_model(self):
        provider = RecordingProvider()
        service = ChatService(provider, report_rag=self.rag, clock=self.clock)
        result = await service.ask(request("SK하이닉스 2019년 실적 왜 나빠졌어?"))
        self.assertIn("2019년 사업보고서를 찾지 못했습니다", result.answer)
        result = await service.ask(request("SK하이닉스 2023년이랑 2024년 실적 왜 달라?"))
        self.assertIn("한 사업연도씩", result.answer)
        result = await service.ask(request("SK하이닉스 2026년 위험 요인"))
        self.assertIn("2025년까지", result.answer)
        self.assertEqual([], provider.histories)

    def test_index_cli_arguments(self):
        from app.report_index import parse_args
        self.assertEqual((["000660"], [2023, 2024, 2025]), parse_args(["000660", "--years", "2023", "2024", "2025"]))
        codes, years = parse_args([])
        self.assertEqual(8, len(codes))
        self.assertEqual([None], years)


class EvidenceNoiseTest(unittest.TestCase):
    def test_history_and_major_shareholder_sections_are_not_indexed(self):
        from app.report_rag import Section, select_sections
        sections = [Section("I. 회사의 개요 > 2. 회사의 연혁", "상호 변경 2012"),
                    Section("X. 대주주 등과의 거래내용", "기계장치 매입"),
                    Section("IV. 이사의 경영진단 및 분석의견", "판가 상승 영향으로 증가"),
                    Section("I. 회사의 개요 > 1. 회사의 개요", "회사 소개")]
        paths = [s.path for s in select_sections(sections)]
        self.assertEqual(["IV. 이사의 경영진단 및 분석의견", "I. 회사의 개요 > 1. 회사의 개요"], paths)

    def test_default_evidence_count_is_three(self):
        import inspect
        from app.report_rag import BusinessReportRag, SEARCH_K
        self.assertEqual(3, SEARCH_K)
        self.assertEqual(SEARCH_K, inspect.signature(BusinessReportRag.search).parameters["k"].default)
