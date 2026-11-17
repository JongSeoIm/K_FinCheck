import json
import unittest
from datetime import date, datetime

import httpx

from app.disclosure_tool import CompanyDisclosureTool, is_disclosure_question
from app.market_date import KST, disclosure_period
from app.memory import ChatMessage
from app.quote_tool import StockQuoteTool
from app.service import ChatService
from tests.test_service import RecordingProvider, request


MONDAY_MORNING = lambda: datetime(2026, 11, 9, 10, 0, tzinfo=KST)


def spring(items, calls=None, prices=None):
    """Spring 가짜: /disclosures 는 items 를, /daily-price 는 prices 를 돌려준다."""
    def handler(req):
        if calls is not None:
            calls.append(f"{req.url.path}?{req.url.query.decode()}")
        if req.url.path.endswith("/disclosures"):
            start, end = req.url.params["from"], req.url.params["to"]
            chosen = [item for item in items if start <= item["receivedDate"] <= end]
            return httpx.Response(200, json={"stockCode": "005930", "companyName": "삼성전자", "from": start, "to": end,
                                             "items": chosen, "truncated": False})
        if req.url.path.endswith("/daily-price") and prices and req.url.params["date"] in prices:
            day = req.url.params["date"]
            close, previous = prices[day]
            return httpx.Response(200, json={"stockCode": "005930", "tradeDate": day, "close": close,
                                             "previousClose": previous, "volume": 1})
        return httpx.Response(404, json={"code": "NO_DATA"})
    return httpx.MockTransport(handler)


def item(receipt, day, title):
    return {"receiptNo": receipt, "receivedDate": day, "reportName": title, "filerName": "삼성전자", "remark": "유",
            "sourceUrl": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={receipt}"}


class NumberProvider(RecordingProvider):
    async def generate(self, history: list[ChatMessage]) -> str:
        self.histories.append(history)
        return "배당금은 주당 1,444원으로 결정됐습니다."


class DisclosureQuestionTest(unittest.TestCase):
    def test_routing(self):
        for text in ["어제 삼성전자 공시 뭐 있어?", "삼성전자 최근 공시", "11월 6일 공시 알려줘", "지난주 공시"]:
            self.assertTrue(is_disclosure_question(text), text)
        for text in ["공시란 뭐야?", "공시 뜻", "삼성전자 사업보고서 공시 내용", "2024년 실적 공시", "삼성전자 주가"]:
            self.assertFalse(is_disclosure_question(text), text)

    def test_period_uses_calendar_dates(self):
        now = MONDAY_MORNING()
        yesterday = disclosure_period("어제 공시", now)  # 일요일도 그대로 (거래일로 바꾸지 않음)
        self.assertEqual((date(2026, 11, 8), date(2026, 11, 8)), (yesterday.start, yesterday.end))
        self.assertEqual("2026-11-08(일) 공시", yesterday.label)
        recent = disclosure_period("최근 공시", now)
        self.assertEqual((date(2026, 10, 11), date(2026, 11, 9)), (recent.start, recent.end))
        last_week = disclosure_period("지난주 공시", now)
        self.assertEqual((date(2026, 11, 2), date(2026, 11, 8)), (last_week.start, last_week.end))
        self.assertEqual(date(2026, 11, 9), disclosure_period("오늘 공시", now).start)
        with self.assertRaises(ValueError):
            disclosure_period("2026-11-20 공시", now)


class DisclosureAnswerTest(unittest.IsolatedAsyncioTestCase):
    async def test_date_question_reads_db_api_and_answers_from_titles(self):
        calls = []
        items = [item("20261106000111", "2026-11-06", "현금ㆍ현물배당결정"),
                 item("20261105000222", "2026-11-05", "기업설명회(IR)개최")]
        provider = RecordingProvider()
        service = ChatService(provider, disclosure_tool=CompanyDisclosureTool("http://spring", spring(items, calls)),
                              clock=MONDAY_MORNING)
        result = await service.ask(request("11월 6일 삼성전자 공시 뭐 있었어?"))
        self.assertEqual(["/api/stocks/005930/disclosures?from=2026-11-06&to=2026-11-06"], calls)
        self.assertEqual("disclosure", result.presentation.type)
        self.assertEqual(["현금ㆍ현물배당결정"], [i["title"] for i in result.presentation.data["items"]])
        self.assertEqual("2026-11-06(금) 공시", result.basis)
        self.assertEqual("OpenDART", result.sources[0].provider)
        sent = provider.histories[0][-1].content
        self.assertIn("현금ㆍ현물배당결정", sent)
        self.assertNotIn("dart.fss.or.kr", sent)  # GPT 에는 링크를 넣지 않는다

    async def test_no_disclosure_answers_without_openai(self):
        provider = RecordingProvider()
        service = ChatService(provider, disclosure_tool=CompanyDisclosureTool("http://spring", spring([])),
                              clock=MONDAY_MORNING)
        result = await service.ask(request("어제 삼성전자 공시"))
        self.assertEqual("삼성전자의 2026-11-08(일) 공시는 없습니다.", result.answer)
        self.assertEqual([], provider.histories)

    async def test_numbers_not_in_titles_are_replaced(self):
        items = [item("20261106000111", "2026-11-06", "현금ㆍ현물배당결정")]
        service = ChatService(NumberProvider(), disclosure_tool=CompanyDisclosureTool("http://spring", spring(items)),
                              clock=MONDAY_MORNING)
        result = await service.ask(request("11월 6일 삼성전자 공시"))
        self.assertNotIn("1,444", result.answer)
        self.assertIn("원문 링크", result.answer)

    async def test_failure_is_reported_not_guessed(self):
        provider = RecordingProvider()
        tool = CompanyDisclosureTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(502)))
        result = await ChatService(provider, disclosure_tool=tool, clock=MONDAY_MORNING).ask(request("삼성전자 최근 공시"))
        self.assertIn("조회하지 못했습니다", result.answer)
        self.assertEqual([], provider.histories)


class CompositeSameDateTest(unittest.IsolatedAsyncioTestCase):
    async def test_price_move_question_gets_price_and_same_day_disclosures(self):
        calls = []
        transport = spring([item("20261106000111", "2026-11-06", "현금ㆍ현물배당결정"),
                            item("20261105000222", "2026-11-05", "다른 날 공시")],
                           calls, prices={"2026-11-06": (51000, 50000)})
        provider = RecordingProvider()
        service = ChatService(provider, quote_tool=StockQuoteTool("http://spring", transport),
                              disclosure_tool=CompanyDisclosureTool("http://spring", transport),
                              clock=lambda: datetime(2026, 11, 9, 18, 0, tzinfo=KST))
        result = await service.ask(request("11월 6일 삼성전자 왜 올랐어?"))
        self.assertIn("/api/stocks/005930/daily-price?date=2026-11-06", calls)
        self.assertIn("/api/stocks/005930/disclosures?from=2026-11-06&to=2026-11-06", calls)
        sections = {section["type"]: section["data"] for section in result.presentation.data["sections"]}
        self.assertEqual(51000, sections["quote"]["price"])
        self.assertEqual(["현금ㆍ현물배당결정"], [i["title"] for i in sections["disclosure"]["items"]])
        sent = json.loads(provider.histories[0][-1].content.split("\n", 1)[1])
        self.assertEqual(["quote", "disclosure"], [section["type"] for section in sent["sections"]])


if __name__ == "__main__":
    unittest.main()
