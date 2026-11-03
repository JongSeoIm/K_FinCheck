import unittest
import httpx

from app.composite import is_composite_question
from app.service import ChatService
from tests.test_service import RecordingProvider, request


class QuoteTool:
    async def get_stock_quote(self, stock):
        return {"name": stock.name, "symbol": stock.symbol, "price": 70000, "changeRate": 1.2,
                "method": "REST", "time_kind": "조회 시각", "basis_at": "2026-11-03T10:00:00Z"}


class NewsTool:
    async def get_company_news(self, stock):
        return {"name": stock.name, "fetchedAt": "2026-11-03T10:00:00Z", "articles": [
            {"title": "기업 소식", "description": "검색 결과 설명", "link": "https://example.com/news", "publishedAt": None}]}


class FailedNews:
    async def get_company_news(self, stock):
        raise httpx.ConnectError("offline")


class FinancialTool:
    def __init__(self):
        self.calls = []

    async def get_financial_result(self, stock, year):
        self.calls.append(year)
        return {"name": stock.name, "businessYear": year or 2025, "basis": "연결 · 연간 사업보고서",
                "sourceUrl": "https://dart.fss.or.kr/", "metrics": []}


class CompositeTest(unittest.IsolatedAsyncioTestCase):
    async def test_combines_quote_news_and_does_not_fetch_financials_by_default(self):
        provider, financial = RecordingProvider(), FinancialTool()
        result = await ChatService(provider, quote_tool=QuoteTool(), financial_tool=financial, news_tool=NewsTool()).ask(request("삼성전자 왜 하락했어?"))
        self.assertEqual("composite", result.presentation.type)
        self.assertEqual(["quote", "news"], [item["type"] for item in result.presentation.data["sections"]])
        self.assertEqual(["KIS", "NAVER"], [item.provider for item in result.sources])
        self.assertEqual([], financial.calls)
        self.assertIn("전제가 조회값과 다르면", provider.histories[0][-1].content)

    async def test_news_failure_keeps_quote_and_warning(self):
        provider = RecordingProvider()
        result = await ChatService(provider, quote_tool=QuoteTool(), news_tool=FailedNews()).ask(request("삼성전자 왜 올랐어?"))
        self.assertEqual(["quote"], [item["type"] for item in result.presentation.data["sections"]])
        self.assertIn("관련 뉴스", result.presentation.data["notes"][0])
        self.assertEqual(1, len(provider.histories))

    async def test_missing_quote_keeps_news_without_inventing_direction(self):
        provider = RecordingProvider()
        result = await ChatService(provider, news_tool=NewsTool()).ask(request("삼성전자 왜 하락했어?"))
        self.assertEqual(["news"], [item["type"] for item in result.presentation.data["sections"]])
        self.assertIn("현재 시세", result.presentation.data["notes"][0])
        self.assertIn("등락 방향을 추측하지", provider.histories[0][-1].content)

    async def test_total_failure_skips_model(self):
        provider = RecordingProvider()
        result = await ChatService(provider, news_tool=FailedNews()).ask(request("삼성전자 왜 하락했어?"))
        self.assertIsNone(result.presentation)
        self.assertEqual([], provider.histories)

    async def test_explicit_financial_request_adds_year_and_table(self):
        financial = FinancialTool()
        result = await ChatService(RecordingProvider(), quote_tool=QuoteTool(), financial_tool=financial, news_tool=NewsTool()).ask(request("삼성전자 2024년 실적과 뉴스 알려줘"))
        self.assertEqual([2024], financial.calls)
        self.assertEqual(["quote", "financial", "news"], [item["type"] for item in result.presentation.data["sections"]])

    async def test_historical_cause_is_not_explained_with_current_quote(self):
        provider = RecordingProvider()
        result = await ChatService(provider, quote_tool=QuoteTool(), news_tool=NewsTool()).ask(request("삼성전자 지난주 왜 하락했어?"))
        self.assertIsNone(result.presentation)
        self.assertIn("과거 특정 기간", result.answer)
        self.assertEqual([], provider.histories)

    def test_single_questions_stay_single(self):
        self.assertFalse(is_composite_question("삼성전자 현재가 알려줘"))
        self.assertFalse(is_composite_question("부채비율이 뭐야?"))
        self.assertTrue(is_composite_question("삼성전자 시세와 뉴스 알려줘"))
