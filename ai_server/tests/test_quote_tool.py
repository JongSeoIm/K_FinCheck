import unittest
from datetime import datetime, timezone

import httpx

from app.quote_tool import StockQuoteTool, resolve_stock
from app.schemas import StockContext
from app.service import ChatService
from tests.test_service import RecordingProvider, request


class QuoteToolTest(unittest.IsolatedAsyncioTestCase):
    async def test_fresh_snapshot_skips_rest(self):
        calls = []
        now = datetime.now(timezone.utc).isoformat()
        def handler(req):
            calls.append(req.url.path)
            return httpx.Response(200, json={"quotes": {"005930": {
                "stockCode": "005930", "price": 70000, "receivedAt": now, "exchangeTime": now,
            }}})
        result = await StockQuoteTool("http://spring", httpx.MockTransport(handler)).get_stock_quote(StockContext(symbol="005930", name="삼성전자"))
        self.assertEqual("체결 시각", result["time_kind"])
        self.assertEqual(["/api/realtime/snapshot"], calls)

    async def test_stale_snapshot_falls_back_to_rest(self):
        def handler(req):
            if "snapshot" in req.url.path:
                return httpx.Response(200, json={"quotes": {"005930": {"receivedAt": "2020-01-01T00:00:00Z"}}})
            return httpx.Response(200, json={"stockCode": "005930", "price": 71000, "changeRate": 1.2})
        result = await StockQuoteTool("http://spring", httpx.MockTransport(handler)).get_stock_quote(StockContext(symbol="005930", name="삼성전자"))
        self.assertEqual("조회 시각", result["time_kind"])
        self.assertEqual(71000, result["price"])

    async def test_failure_does_not_ask_llm_to_guess(self):
        provider = RecordingProvider()
        tool = StockQuoteTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(503)))
        result = await ChatService(provider, quote_tool=tool).ask(request("삼성전자 현재가 알려줘"))
        self.assertIn("확인할 수 없습니다", result.answer)
        self.assertEqual([], provider.histories)
        self.assertEqual([], result.sources)

    async def test_quote_context_and_source_are_forwarded(self):
        provider = RecordingProvider()
        def handler(req):
            if "snapshot" in req.url.path:
                return httpx.Response(404)
            return httpx.Response(200, json={"stockCode": "000660", "price": 120000})
        tool = StockQuoteTool("http://spring", httpx.MockTransport(handler))
        result = await ChatService(provider, quote_tool=tool).ask(request("SK하이닉스 현재가 알려줘"))
        self.assertEqual("KIS", result.sources[0].provider)
        self.assertIn("120000", provider.histories[0][-1].content)
        self.assertIn("SK하이닉스", result.sources[0].label)
        self.assertEqual("quote", result.presentation.type)
        self.assertEqual(120000, result.presentation.data["price"])

    def test_explicit_stock_overrides_selection(self):
        stock = resolve_stock("SK하이닉스 현재가", StockContext(symbol="005930", name="삼성전자"))
        self.assertEqual("000660", stock.symbol)

    def test_multiple_stocks_are_not_silently_mixed(self):
        with self.assertRaises(ValueError):
            resolve_stock("삼성전자 SK하이닉스 현재가", StockContext(symbol="005930", name="삼성전자"))
