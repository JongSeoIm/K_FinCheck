import unittest

from app.service import ChatService
from app.schemas import StockContext
from app.quote_tool import resolve_stock
from tests.test_service import RecordingProvider, request
from tests.test_composite import QuoteTool, PolicyTool, FinancialTool


class ContextTest(unittest.IsolatedAsyncioTestCase):
    async def test_supplied_database_history_restores_context_and_overrides_memory(self):
        from app.schemas import HistoryMessage
        service = ChatService(RecordingProvider(), quote_tool=QuoteTool())
        supplied = [HistoryMessage(role="user", content="하이닉스 현재가"), HistoryMessage(role="assistant", content="조회 결과입니다.")]
        result = await service.ask(request("현재가").model_copy(update={"history": supplied}))
        self.assertEqual("000660", result.presentation.data["symbol"])
        fresh = await service.ask(request("그럼 하닉은?").model_copy(update={"history": []}))
        self.assertIsNone(fresh.presentation)
        self.assertEqual([], service.history(1, "conversation_001"))

    async def test_quote_followup_switches_company_and_keeps_intent(self):
        service = ChatService(RecordingProvider(), quote_tool=QuoteTool())
        await service.ask(request("삼성전자 현재가 알려줘"))
        for question in ["그럼 하이닉스는?", "그럼 SK하이닉스는?"]:
            result = await service.ask(request(question))
            self.assertEqual("quote", result.presentation.type)
            self.assertEqual("000660", result.presentation.data["symbol"])

    async def test_financial_followup_keeps_year(self):
        financial = FinancialTool()
        service = ChatService(RecordingProvider(), financial_tool=financial)
        await service.ask(request("삼성전자 2024년 실적"))
        result = await service.ask(request("하이닉스는?"))
        self.assertEqual("financial", result.presentation.type)
        self.assertEqual([2024, 2024], financial.calls)
        self.assertIn("하이닉스", result.presentation.data["name"])

    async def test_changed_intent_uses_last_company(self):
        service = ChatService(RecordingProvider(), quote_tool=QuoteTool(), financial_tool=FinancialTool())
        await service.ask(request("하이닉스 현재가"))
        result = await service.ask(request("그 회사 실적은?"))
        self.assertEqual("financial", result.presentation.type)
        self.assertIn("하이닉스", result.presentation.data["name"])

    async def test_other_account_and_conversation_cannot_inherit(self):
        service = ChatService(RecordingProvider(), quote_tool=QuoteTool())
        await service.ask(request("삼성전자 현재가"))
        for req in [request("그럼 하이닉스는?", "another_chat_001"), request("그럼 하이닉스는?").model_copy(update={"user_id": 2})]:
            result = await service.ask(req)
            self.assertIsNone(result.presentation)
            self.assertIn("어떤 정보", result.answer)

    async def test_unrelated_followup_stays_out_of_scope(self):
        provider = RecordingProvider()
        service = ChatService(provider, quote_tool=QuoteTool())
        await service.ask(request("삼성전자 현재가"))
        result = await service.ask(request("오늘 저녁 뭐 먹을까?"))
        self.assertEqual("out_of_scope", result.status)
        self.assertEqual(1, len(provider.histories))

    async def test_unknown_company_does_not_call_tool(self):
        provider = RecordingProvider()
        service = ChatService(provider, quote_tool=QuoteTool())
        for q in ["애플 현재가 알려줘", "테슬라 실적 알려줘", "애플 관련 정책 알려줘", "그럼 애플은?"]:
            result = await service.ask(request(q))
            self.assertIsNone(result.presentation)
            self.assertEqual([], provider.histories)

    def test_aliases_and_default_selection(self):
        selected = StockContext(symbol="005930", name="삼성전자")
        self.assertEqual("035420", resolve_stock("네이버 현재가", selected).symbol)
        self.assertEqual("005380", resolve_stock("현대자동차 실적", selected).symbol)
        self.assertEqual("005930", resolve_stock("현재가 알려줘", selected).symbol)

    async def test_financial_word_does_not_bypass_historical_period(self):
        provider = RecordingProvider()
        service = ChatService(provider, quote_tool=QuoteTool(), policy_tool=PolicyTool(), financial_tool=FinancialTool())
        for q in ["삼성전자 지난주 왜 하락했어? 실적도 알려줘", "삼성전자 어제 하락 이유와 2024년 실적 알려줘"]:
            result = await service.ask(request(q))
            self.assertIsNone(result.presentation)
            self.assertIn("과거 특정 기간", result.answer)
        self.assertEqual([], provider.histories)
