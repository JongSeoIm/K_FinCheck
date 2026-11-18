"""사업보고서 RAG + 실적 '왜' 질문 (친구 브랜치의 전제 검사·키워드 보충·안전 답변을 합친 부분)."""
import tempfile
import unittest
from datetime import datetime
from types import SimpleNamespace

import httpx
from openai import OpenAIError

from app.financial_tool import FinancialResultTool
from app.market_date import KST
from app.rag import OpenAIEmbedder
from app.report_rag import (BusinessReportRag, BusinessReportTool, ReportStore, Section, build_chunks, build_query,
                            keyword_terms, numeric_table_share)
from app.service import ChatService
from tests.test_financial_reason import report
from tests.test_report_rag import FakeEmbeddings, request, spring
from tests.test_service import RecordingProvider


def financial(operating="-7730000000000", net="-9137000000000"):
    """?year= 에 맞춰 그 연도 실적을 돌려주는 Spring 흉내."""
    def handler(req):
        year = int(req.url.params.get("year") or 2025)
        return httpx.Response(200, json=report(year=year, operating=operating, net=net))
    return FinancialResultTool("http://spring", httpx.MockTransport(handler))


class FailingProvider(RecordingProvider):
    async def generate(self, history):
        self.histories.append(history)
        raise OpenAIError("down")


class ReportReasonTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.calls = []
        self.rag = BusinessReportRag(BusinessReportTool("http://spring", spring(self.calls)),
                                     ReportStore(f"{self.folder.name}/reports.sqlite3"),
                                     OpenAIEmbedder(SimpleNamespace(embeddings=FakeEmbeddings())))
        self.clock = lambda: datetime(2026, 11, 10, 10, 0, tzinfo=KST)

    async def asyncTearDown(self):
        self.folder.cleanup()

    def service(self, provider, **kwargs):
        return ChatService(provider, financial_tool=financial(**kwargs), report_rag=self.rag, clock=self.clock)

    async def test_loss_reason_uses_report_with_numbers_and_keyword_chunk_first(self):
        provider = RecordingProvider()
        result = await self.service(provider).ask(request("하이닉스 2023년 왜 적자야?"))
        self.assertEqual("report", result.presentation.type)
        data = result.presentation.data
        self.assertEqual(2023, data["businessYear"])
        self.assertEqual(2023, data["financial"]["businessYear"])
        self.assertEqual({"영업이익": "적자", "당기순이익": "적자"}, data["profitStatus"])
        self.assertIn("영업손실", data["chunks"][0]["text"])  # 키워드 보충 조각이 맨 앞
        self.assertIn("경영진단", data["chunks"][0]["section"])
        context = provider.histories[0][-1].content
        self.assertIn("빠짐없이", context)
        self.assertIn("profitStatus", context)

    async def test_wrong_premise_is_corrected_without_download_or_model(self):
        provider = RecordingProvider()
        result = await self.service(provider, operating="1000000000000", net="800000000000").ask(request("하이닉스 2023년 왜 적자야?"))
        self.assertIn("적자가 아니에요", result.answer)
        self.assertEqual("financial", result.presentation.type)
        self.assertFalse(any("/document" in call for call in self.calls))
        self.assertEqual([], provider.histories)

    async def test_model_failure_falls_back_to_numbers_only_answer(self):
        result = await self.service(FailingProvider()).ask(request("하이닉스 2023년 왜 적자야?"))
        self.assertIn("적자입니다", result.answer)
        self.assertIn("확인해 드릴 수 없습니다", result.answer)
        self.assertEqual("2023년 사업연도 공시 기준", result.basis)

    async def test_followup_reason_keeps_company_and_year(self):
        provider = RecordingProvider()
        service = self.service(provider)
        await service.ask(request("2023년 하이닉스 실적 알려줘"))
        result = await service.ask(request("그럼 왜 적자냐고"))
        self.assertEqual("report", result.presentation.type)
        self.assertEqual(2023, result.presentation.data["businessYear"])
        self.assertIn("year=2023", " ".join(self.calls))

    async def test_non_financial_report_question_is_unchanged(self):
        provider = RecordingProvider()
        result = await self.service(provider).ask(request("SK하이닉스 위험 요인이 뭐야?"))
        self.assertEqual("report", result.presentation.type)
        self.assertIsNone(result.presentation.data["financial"])


class RetrievalHelpersTest(unittest.TestCase):
    def test_query_drops_code_and_year_and_adds_reason_hint(self):
        query = build_query("SK하이닉스", 2023, "SK하이닉스 000660 2023 왜 적자야?")
        self.assertNotIn("000660", query)
        self.assertTrue(query.startswith("SK하이닉스 2023년 사업보고서"))
        self.assertIn("원인", query)
        self.assertEqual("삼성전자 사업보고서 위험 요인", build_query("삼성전자", None, "위험 요인"))

    def test_keyword_terms_only_for_reason_questions(self):
        self.assertEqual([["영업손실"], ["적자전환"]], keyword_terms("왜 적자야?"))
        self.assertEqual("매출", keyword_terms("매출이 왜 줄었어?")[0][0])
        self.assertEqual([], keyword_terms("위험 요인이 뭐야?"))
        self.assertEqual([], keyword_terms("2023년 실적 알려줘"))

    def test_numeric_tables_are_dropped_but_named_tables_stay(self):
        numbers = " | ".join(["1,234", "5,678", "(12.3)", "△45", "2023", "2024"] * 5)
        self.assertGreater(numeric_table_share(numbers), 0.6)
        self.assertEqual(0.0, numeric_table_share("문장으로 된 설명입니다."))
        self.assertLess(numeric_table_share("부문 | 매출 비중 | DRAM | 77.4% | NAND | 20.1%"), 0.6)
        chunks = build_chunks([Section("II. 사업의 내용 > 표", numbers), Section("II. 사업의 내용 > 글", "DRAM 판매가 늘었습니다.")])
        self.assertEqual(["II. 사업의 내용 > 글"], [section for section, _ in chunks])


if __name__ == "__main__":
    unittest.main()
