import tempfile
import unittest
from datetime import datetime
from types import SimpleNamespace

import httpx

from app.market_date import KST, DateBasis
from app.news_tool import CompanyNewsTool
from app.quote_tool import StockQuoteTool
from app.rag import EmbeddingCache, OpenAIEmbedder, chunk_text
from app.schemas import StockContext
from app.service import ChatService
from app.usage import start_usage
from tests.test_service import RecordingProvider, request


SAMSUNG = StockContext(symbol="005930", name="삼성전자")


class FakeEmbeddings:
    """글자별 벡터. 같은 글자가 많을수록 가깝다."""
    def __init__(self):
        self.calls = 0

    async def create(self, model, input, timeout=None):
        self.calls += 1
        vocab = "삼성전자배당반도체HBM실적"
        data = [SimpleNamespace(embedding=[text.count(ch) + 0.01 for ch in vocab]) for text in input]
        return SimpleNamespace(data=data, usage=SimpleNamespace(total_tokens=10 * len(input)))


class ChunkTest(unittest.TestCase):
    def test_long_text_is_split_with_overlap(self):
        pieces = chunk_text("가" * 1200, size=500, overlap=100)
        self.assertEqual([500, 500, 400], [len(p) for p in pieces])


class EmbeddingCacheTest(unittest.IsolatedAsyncioTestCase):
    async def test_embedding_cache_avoids_second_charge(self):
        with tempfile.TemporaryDirectory() as folder:
            client = SimpleNamespace(embeddings=FakeEmbeddings())
            embedder = OpenAIEmbedder(client, cache=EmbeddingCache(f"{folder}/e.sqlite3"))
            usage = start_usage()
            first = await embedder.embed(["삼성전자 배당", "반도체 HBM 실적"])
            second = await embedder.embed(["삼성전자 배당", "반도체 HBM 실적"])
            self.assertEqual(first, second)
            self.assertEqual(1, client.embeddings.calls)
            self.assertEqual(20, usage.embedding_tokens)


def spring(feed, calls=None):
    def handler(req):
        if calls is not None:
            calls.append(str(req.url))
        return httpx.Response(200, json=feed)
    return httpx.MockTransport(handler)


def article(i, day):
    return {"title": f"삼성전자 기사 {i}", "description": "설명", "link": f"https://example.com/{i}",
            "publishedAt": f"{day}T01:00:00Z"}


class NewsTest(unittest.IsolatedAsyncioTestCase):
    """뉴스는 최신순 검색 결과만 쓴다. 날짜를 지정한 뉴스는 지원하지 않는다."""

    async def test_latest_news_has_basis_and_naver_source(self):
        feed = {"stockCode": "005930", "fetchedAt": "2026-11-10T01:00:00Z", "articles": [article(1, "2026-11-09")]}
        calls, provider = [], RecordingProvider()
        clock = lambda: datetime(2026, 11, 10, 8, 0, tzinfo=KST)
        tool = CompanyNewsTool("http://spring", spring(feed, calls))
        result = await ChatService(provider, news_tool=tool, clock=clock).ask(request("오늘 삼성전자 뉴스"))
        self.assertEqual("news", result.presentation.type)
        self.assertEqual("최신순 검색 결과 기준", result.basis)
        self.assertEqual(["NAVER"], [s.provider for s in result.sources])
        self.assertNotIn("from=", calls[0])
        self.assertIn("2026-11-10", provider.histories[0][-2].content)  # 오늘 날짜 안내

    async def test_past_dated_news_is_rejected_without_model(self):
        feed = {"stockCode": "005930", "fetchedAt": "x", "articles": [article(1, "2026-11-09")]}
        clock = lambda: datetime(2026, 11, 10, 10, 0, tzinfo=KST)
        for text in ["어제 삼성전자 뉴스", "삼성전자 지난주 뉴스", "11월 6일 삼성전자 기사"]:
            calls, provider = [], RecordingProvider()
            tool = CompanyNewsTool("http://spring", spring(feed, calls))
            result = await ChatService(provider, news_tool=tool, clock=clock).ask(request(text))
            self.assertIn("날짜를 지정한 뉴스 조회는 지원하지 않습니다", result.answer, text)
            self.assertEqual([], calls, text)
            self.assertEqual([], provider.histories, text)


def daily_price(prices):
    """Spring /api/stocks/{code}/daily-price 가짜. prices = {날짜: (종가, 직전 종가)}"""
    def handler(req):
        date = req.url.params.get("date")
        if req.url.path != "/api/stocks/005930/daily-price" or date not in prices:
            return httpx.Response(404, json={"code": "NO_DATA"})
        close, previous = prices[date]
        return httpx.Response(200, json={"stockCode": "005930", "tradeDate": date, "open": close - 500, "high": close + 1000,
                                         "low": close - 1000, "close": close, "volume": 9, "previousClose": previous,
                                         "source": "DB"})
    return handler


class PastQuoteTest(unittest.IsolatedAsyncioTestCase):
    async def test_daily_close_for_past_date_comes_from_db_api(self):
        paths = []
        fake = daily_price({"2026-11-06": (51000, 50000)})
        def handler(req):
            paths.append(f"{req.url.path}?{req.url.query.decode()}")
            return fake(req)
        basis = DateBasis(datetime(2026, 11, 6).date(), datetime(2026, 11, 6).date(), False, "x")
        quote = await StockQuoteTool("http://spring", httpx.MockTransport(handler)).get_stock_quote(SAMSUNG, basis)
        self.assertEqual((51000, 1000, 2.0, "일봉 종가", "DB 일봉"),
                         (quote["price"], quote["priceChange"], quote["changeRate"], quote["time_kind"], quote["method"]))
        self.assertEqual(["/api/stocks/005930/daily-price?date=2026-11-06"], paths)

    async def test_missing_day_is_failure_not_guess(self):
        basis = DateBasis(datetime(2026, 11, 6).date(), datetime(2026, 11, 6).date(), False, "x")
        tool = StockQuoteTool("http://spring", httpx.MockTransport(daily_price({})))
        with self.assertRaises(ValueError):
            await tool.get_stock_quote(SAMSUNG, basis)

    async def test_other_day_in_response_is_rejected(self):
        # 직전 거래일은 7일 안에서만 받아들인다. 요청보다 뒤 날짜나 너무 앞 날짜는 거부한다.
        basis = DateBasis(datetime(2026, 11, 6).date(), datetime(2026, 11, 6).date(), False, "x")
        for trade_date in ("2026-11-09", "2026-10-28"):
            tool = StockQuoteTool("http://spring", httpx.MockTransport(lambda req, d=trade_date: httpx.Response(200, json={
                "stockCode": "005930", "tradeDate": d, "close": 50000})))
            with self.assertRaises(ValueError):
                await tool.get_stock_quote(SAMSUNG, basis)

    async def test_previous_trading_day_from_spring_is_marked(self):
        basis = DateBasis(datetime(2026, 11, 9).date(), datetime(2026, 11, 9).date(), False, "x")
        tool = StockQuoteTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(200, json={
            "stockCode": "005930", "tradeDate": "2026-11-06", "close": 51000, "previousClose": 50000})))
        quote = await tool.get_stock_quote(SAMSUNG, basis)
        self.assertEqual(("2026-11-06", "2026-11-09"), (quote["tradeDate"], quote["fallbackFrom"]))
        self.assertEqual("2026-11-06 종가", quote["basis_at"])

    async def test_unlisted_holiday_answer_says_previous_trading_day(self):
        # 11/9(월)가 휴장일 목록에 없는 임시공휴일이라 Spring 이 11/6(금) 일봉을 돌려준 경우
        tool = StockQuoteTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(200, json={
            "stockCode": "005930", "tradeDate": "2026-11-06", "close": 51000, "previousClose": 50000})))
        clock = lambda: datetime(2026, 11, 10, 10, 35, tzinfo=KST)
        result = await ChatService(RecordingProvider(), quote_tool=tool, clock=clock).ask(request("어제 삼성전자 주가"))
        self.assertEqual(51000, result.presentation.data["price"])
        self.assertEqual("2026-11-06(금) 종가 기준", result.basis)
        self.assertTrue(result.answer.startswith("2026-11-09(월)은 거래 기록이 없어 직전 거래일인 2026-11-06(금) 종가로 안내합니다."))

    async def test_composite_question_also_switches_to_previous_trading_day(self):
        tool = StockQuoteTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(200, json={
            "stockCode": "005930", "tradeDate": "2026-11-06", "close": 51000, "previousClose": 50000})))
        clock = lambda: datetime(2026, 11, 10, 10, 35, tzinfo=KST)
        result = await ChatService(RecordingProvider(), quote_tool=tool, clock=clock).ask(request("어제 삼성전자 왜 올랐어?"))
        self.assertEqual("composite", result.presentation.type)
        self.assertEqual("2026-11-06(금) 종가 기준", result.basis)
        self.assertIn("직전 거래일인 2026-11-06(금)", result.answer)

    async def test_monday_morning_question_uses_friday_close(self):
        provider = RecordingProvider()
        clock = lambda: datetime(2026, 11, 9, 8, 30, tzinfo=KST)
        tool = StockQuoteTool("http://spring", httpx.MockTransport(daily_price({"2026-11-06": (51000, 50000)})))
        result = await ChatService(provider, quote_tool=tool, clock=clock).ask(request("오늘 삼성전자 주가"))
        self.assertEqual(51000, result.presentation.data["price"])
        self.assertEqual("2026-11-06(금) 종가 기준", result.basis)
        self.assertTrue(result.answer.startswith("아직 장 시작 전"))

    async def test_range_quote_is_rejected(self):
        provider = RecordingProvider()
        result = await ChatService(provider, quote_tool=StockQuoteTool("http://spring")).ask(request("삼성전자 지난주 주가"))
        self.assertIn("날짜 하나로", result.answer)
        self.assertEqual([], provider.histories)


class TimeSensitiveTest(unittest.IsolatedAsyncioTestCase):
    async def test_current_question_without_tool_is_told_not_to_guess(self):
        provider = RecordingProvider()
        await ChatService(provider).ask(request("요즘 기준금리 몇 퍼센트야?"))
        self.assertIn("학습 지식은 2025년 8월까지", provider.histories[0][-1].content)


class DateWithoutCompanyTest(unittest.IsolatedAsyncioTestCase):
    async def test_date_words_are_not_company_names(self):
        tool = StockQuoteTool("http://spring", httpx.MockTransport(daily_price({"2026-11-09": (199000, 190000)})))
        clock = lambda: datetime(2026, 11, 10, 10, 35, tzinfo=KST)
        service = ChatService(RecordingProvider(), quote_tool=tool, clock=clock)
        for text in ["어제주가알려줘", "어제 주가 알려줘", "11월 9일 주가", "11/9 주가"]:
            result = await service.ask(request(text))
            self.assertEqual("2026-11-09(월) 종가 기준", result.basis, text)
            self.assertEqual("005930", result.presentation.data["symbol"])
        result = await service.ask(request("애플 주가"))  # 모르는 기업은 여전히 거부
        self.assertIsNone(result.presentation)
