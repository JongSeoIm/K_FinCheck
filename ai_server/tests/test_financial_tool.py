import unittest
import httpx

from app.financial_tool import FinancialResultTool, financial_year, is_financial_question
from app.service import ChatService
from tests.test_service import RecordingProvider, request


def report():
    return {"stockCode": "005930", "businessYear": 2025, "previousYear": 2024,
            "fsDiv": "CFS", "reportCode": "11011", "currency": "KRW",
            "sourceUrl": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260310002820",
            "metrics": [{"label": "매출액", "status": "AVAILABLE", "currentAmount": "333605940000000",
                         "previousAmount": "300870900000000", "growthRate": 10.88, "comparison": "RATE"},
                        {"label": "영업이익", "status": "MISSING", "currentAmount": None}]}


class FinancialToolTest(unittest.IsolatedAsyncioTestCase):
    async def test_year_amount_missing_and_source(self):
        paths = []
        def handler(req):
            paths.append(str(req.url))
            return httpx.Response(200, json=report())
        provider = RecordingProvider()
        tool = FinancialResultTool("http://spring", httpx.MockTransport(handler))
        result = await ChatService(provider, financial_tool=tool).ask(request("삼성전자 2025년 실적 알려줘"))
        self.assertIn("year=2025", paths[0])
        context = provider.histories[0][-1].content
        self.assertIn("3336059.4", context)
        self.assertIn('"current": null', context)
        self.assertEqual("OpenDART", result.sources[0].provider)
        self.assertEqual("2025년 사업연도", result.sources[0].basis_at)
        self.assertIn("dart.fss.or.kr", result.sources[0].url)
        self.assertEqual("financial", result.presentation.type)
        self.assertEqual("3336059.4", result.presentation.data["metrics"][0]["current"])
        self.assertIsNone(result.presentation.data["metrics"][1]["current"])

    async def test_failure_does_not_call_model(self):
        provider = RecordingProvider()
        tool = FinancialResultTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(503)))
        result = await ChatService(provider, financial_tool=tool).ask(request("삼성전자 최근 실적 알려줘"))
        self.assertIn("확인할 수 없습니다", result.answer)
        self.assertEqual([], provider.histories)
        self.assertIsNone(result.presentation)

    async def test_unsupported_conditions_do_not_query(self):
        provider = RecordingProvider()
        def handler(req):
            self.fail("지원하지 않는 조건으로 조회하면 안 됩니다.")
        tool = FinancialResultTool("http://spring", httpx.MockTransport(handler))
        service = ChatService(provider, financial_tool=tool)
        for question in ["삼성전자 분기 실적", "삼성전자 별도 실적", "NAVER 최근 실적"]:
            result = await service.ask(request(question))
            self.assertIn("지원", result.answer)
        self.assertEqual([], provider.histories)

    async def test_mismatched_year_is_rejected(self):
        provider = RecordingProvider()
        tool = FinancialResultTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(200, json=report())))
        await ChatService(provider, financial_tool=tool).ask(request("삼성전자 2024년 실적"))
        self.assertEqual([], provider.histories)

    async def test_numeric_narrative_is_replaced_but_table_data_stays(self):
        class NumericProvider:
            async def generate(self, history):
                return "매출액은 3336059.4억 원입니다."
        tool = FinancialResultTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(200, json=report())))
        result = await ChatService(NumericProvider(), financial_tool=tool).ask(request("삼성전자 실적"))
        self.assertNotIn("3336059.4", result.answer)
        self.assertEqual("3336059.4", result.presentation.data["metrics"][0]["current"])

    def test_concepts_and_year_selection(self):
        self.assertFalse(is_financial_question("영업이익이 뭐야?"))
        self.assertIsNone(financial_year("최근 실적"))
        self.assertEqual(2024, financial_year("2024년 실적"))
        with self.assertRaises(ValueError):
            financial_year("2024년 2025년 실적 비교")
