import unittest
from datetime import datetime
from zoneinfo import ZoneInfo
import httpx
from app.financial_tool import FinancialResultTool, financial_years
from app.service import ChatService
from tests.test_service import RecordingProvider, request
from tests.test_financial_tool import report


class FinancialComparisonTest(unittest.IsolatedAsyncioTestCase):
    def test_year_ranges_recent_three_and_limits(self):
        self.assertEqual([2023, 2024, 2025], financial_years("삼성전자 2023년부터 2025년 실적 비교"))
        self.assertEqual([2023, 2025], financial_years("2023년과 2025년 실적 비교"))
        latest = datetime.now(ZoneInfo("Asia/Seoul")).year - 1
        self.assertEqual(list(range(latest - 2, latest + 1)), financial_years("최근 3개년 실적"))
        for text in ["최근 4년 실적", "2020~2025 실적", "분기 실적", "2014년 실적"]:
            with self.assertRaises(ValueError): financial_years(text)

    async def test_three_years_keep_sources_and_explicit_missing_year(self):
        paths = []
        def handler(req):
            year = int(req.url.params["year"])
            paths.append(year)
            if year == 2024: return httpx.Response(503)
            data = report(); data.update(businessYear=year, previousYear=year-1)
            return httpx.Response(200, json=data)
        provider = RecordingProvider()
        service = ChatService(provider, financial_tool=FinancialResultTool("http://spring", httpx.MockTransport(handler)))
        answer = await service.ask(request("삼성전자 2023년부터 2025년 실적 비교"))
        self.assertEqual([2023,2024,2025], paths)
        self.assertEqual("financial_comparison", answer.presentation.type)
        self.assertEqual([2023,2024,2025], answer.presentation.data["years"])
        self.assertEqual(2,len(answer.presentation.data["reports"]))
        self.assertIn("2024", answer.presentation.data["notes"][0])
        self.assertEqual(["2023년 사업연도", "2025년 사업연도"], [s.basis_at for s in answer.sources])
        self.assertEqual(1, len(provider.histories))

    async def test_all_missing_does_not_call_model(self):
        provider = RecordingProvider()
        tool = FinancialResultTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(503)))
        answer = await ChatService(provider, financial_tool=tool).ask(request("삼성전자 최근 3년 실적"))
        self.assertIsNone(answer.presentation)
        self.assertEqual([], provider.histories)

    async def test_recent_years_use_selected_company(self):
        def handler(req):
            year = int(req.url.params["year"])
            data = report(); data.update(businessYear=year, previousYear=year-1)
            return httpx.Response(200, json=data)
        tool = FinancialResultTool("http://spring", httpx.MockTransport(handler))
        answer = await ChatService(RecordingProvider(), financial_tool=tool).ask(request("최근 3개년 실적 비교"))
        self.assertEqual("financial_comparison", answer.presentation.type)
        self.assertEqual(3, len(answer.presentation.data["reports"]))
