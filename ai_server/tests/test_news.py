import asyncio
import gzip
import unittest
from datetime import datetime, timedelta, timezone

import httpx

from app.news_tool import CompanyNewsTool
from app.schemas import StockContext
from news.classify import DIRECT, INDUSTRY, NONE, classify
from news.collector import RssCollector
from news.dictionary import STOCKS
from news.http import PoliteClient, parse_content_signal
from news.rss import parse_titles
from news.store import NewsItem, NewsStore
from news.summary import MockSummarizer, SummaryError, SummaryService, UsageLimiter, extract_body
from news.terms import ai_input_allowed


SITE = "https://biz.example.com"
FEEDS = (("증권", f"{SITE}/rss/stock/"), ("전체", f"{SITE}/rss/"))
ROBOTS = "User-agent: *\nContent-Signal: search=yes, ai-input=yes, ai-train=no\nAllow: /\nDisallow: /test*\n"
PAGE = ("<html><h1>제목</h1><div itemprop='articleBody'><p>삼성전자가 HBM 공급 계약을 맺었다고 밝혔다. "
        "회사는 내년 생산을 늘릴 계획이다.</p><figure><figcaption>사진 설명</figcaption></figure><script>x()</script>"
        "<p>업계는 메모리 수요가 이어질 것으로 본다.</p><p>홍길동 기자 hong@example.com</p>"
        "<p>Copyright ⓒ 무단 전재 금지</p></div><footer>푸터</footer></html>")


def rss(*items):
    xml = "".join(f"<item><title><![CDATA[{t}]]></title><link>{l}</link><pubDate>{d}</pubDate>"
                  "<description><![CDATA[설명 문장]]></description>"
                  "<content:encoded><![CDATA[<p>기사 본문 전체</p>]]></content:encoded></item>" for t, l, d in items)
    return ('<?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">'
            f"<channel><title>t</title>{xml}</channel></rss>").encode()


FEED = rss(("삼성전자 &amp; HBM 공급", f"{SITE}/a/1", "Thu, 12 Nov 2026 03:00:00 +0000"),
           ("반도체 업황 회복", f"{SITE}/a/2", "Thu, 12 Nov 2026 04:00:00 +0000"),
           ("외부 링크", "https://evil.example.org/x", "Thu, 12 Nov 2026 05:00:00 +0000"),
           ("중복", f"{SITE}/a/1", "Thu, 12 Nov 2026 05:00:00 +0000"))


class FakeClock:
    def __init__(self):
        self.now, self.sleeps = 1000.0, []

    def __call__(self):
        return self.now

    async def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def site(robots=ROBOTS, page=PAGE, log=None, feed=FEED):
    def handler(request):
        if log is not None:
            log.append(request.url.path)
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text=robots)
        if request.url.path.startswith("/a/"):
            return httpx.Response(200, text=page, headers={"Content-Type": "text/html; charset=utf-8"})
        return httpx.Response(200, content=feed, headers={"ETag": '"v1"'})
    return handler


def client(handler, **kwargs):
    clock = FakeClock()
    return PoliteClient(httpx.MockTransport(handler), sleep=clock.sleep, clock=clock, **kwargs), clock


class TermsTest(unittest.TestCase):
    def test_ai_input_must_be_explicit_yes(self):
        self.assertTrue(ai_input_allowed({"ai-input": "yes"}))
        self.assertFalse(ai_input_allowed({"ai-input": "no"}))
        self.assertFalse(ai_input_allowed({"search": "yes"}))   # 표시가 없으면 중단
        self.assertFalse(ai_input_allowed(None))

    def test_content_signal_only_from_star_group(self):
        text = "User-agent: GPTBot\nContent-Signal: ai-input=no\nDisallow: /\n\nUser-agent: *\nContent-Signal: ai-input=yes\n"
        self.assertEqual({"ai-input": "yes"}, parse_content_signal(text))


class RssTest(unittest.TestCase):
    def test_only_title_link_time(self):
        items = parse_titles(FEED, FEEDS[0][1], "조선비즈")
        self.assertEqual(["반도체 업황 회복", "삼성전자 & HBM 공급"], [i.title for i in items])
        self.assertEqual({"title", "link", "published_at", "source_name", "collected_at"}, set(vars(items[0])))
        self.assertNotIn("본문", str(items))
        self.assertNotIn("설명", str(items))

    def test_japanese_and_english_editions_are_excluded(self):
        xml = rss(("휴온스, 3분기 적자전환", f"{SITE}/medicine/2026/11/12/A/", "Thu, 12 Nov 2026 03:00:00 +0000"),
                  ("ヒューオンス7-9月期赤字転落", f"{SITE}/jp/jp-medicine/2026/11/12/B/", "Thu, 12 Nov 2026 03:00:00 +0000"),
                  ("ヒューオンス赤字", f"{SITE}/medicine/2026/11/12/C/", "Thu, 12 Nov 2026 03:00:00 +0000"),
                  ("GS Retail app tops 5 million users", f"{SITE}/en/en-retail/2026/11/12/D/", "Thu, 12 Nov 2026 03:00:00 +0000"))
        self.assertEqual(["휴온스, 3분기 적자전환"], [i.title for i in parse_titles(xml, FEEDS[0][1], "조선비즈")])

    def test_pubdate_without_space_after_comma(self):
        xml = rss(("제목", f"{SITE}/a/9", "Thu,12 Nov 2026 12:57:55 +0900"))
        self.assertEqual("2026-11-12T03:57:55+00:00", parse_titles(xml, FEEDS[0][1], "조선비즈")[0].published_at.isoformat())


class StoreTest(unittest.TestCase):
    def item(self, link, at, published=None):
        return NewsItem("제목", link, published, "조선비즈", at)

    def test_dedupe_and_5day_expiry_with_summary(self):
        now = datetime(2026, 11, 12, tzinfo=timezone.utc)
        store = NewsStore()
        self.assertEqual(1, store.add([self.item("a", now), self.item("a", now)], now=now))
        store.keep_summary("a", {"summary": "요약"}, now=now)
        self.assertEqual(1, store.size(now=now + timedelta(days=4, hours=23)))
        self.assertEqual(0, store.size(now=now + timedelta(days=5)))
        self.assertIsNone(store.cached_summary("a", now=now + timedelta(days=5)))

    def test_keeps_only_articles_published_within_5_days(self):
        now = datetime(2026, 11, 12, tzinfo=timezone.utc)
        store = NewsStore()
        added = store.add([self.item("new", now, now - timedelta(days=1)),
                           self.item("old", now, now - timedelta(days=6))], now=now)   # 6일 전 발행은 받지 않음
        self.assertEqual(1, added)
        self.assertEqual(0, store.size(now=now + timedelta(days=4)))   # 발행 후 5일이 지나면 삭제

    def test_summary_kept_three_hours_only_for_listed_items(self):
        now = datetime(2026, 11, 12, tzinfo=timezone.utc)
        store = NewsStore()
        store.add([self.item("a", now)], now=now)
        store.keep_summary("a", {"summary": "요약"}, now=now)
        store.keep_summary("not-listed", {"summary": "요약"}, now=now)
        self.assertIsNotNone(store.cached_summary("a", now=now + timedelta(hours=2)))
        self.assertIsNone(store.cached_summary("a", now=now + timedelta(hours=3)))
        self.assertIsNone(store.cached_summary("not-listed", now=now))

    def test_latest_order(self):
        now = datetime(2026, 11, 12, tzinfo=timezone.utc)
        store = NewsStore()
        store.add([self.item("old", now, now - timedelta(hours=2)), self.item("new", now, now)], now=now)
        self.assertEqual(["new", "old"], [i.link for i in store.latest(now=now)])


class ClassifyTest(unittest.TestCase):
    def test_direct_industry_none(self):
        samsung = STOCKS["005930"]
        self.assertEqual(DIRECT, classify("삼성전자, HBM 공급 계약", samsung))
        self.assertEqual(DIRECT, classify("'삼전' 외국인 순매수", samsung))
        self.assertEqual(INDUSTRY, classify("반도체 업황 회복 기대", samsung))
        self.assertEqual(NONE, classify("케이크 사전예약 시작", samsung))

    def test_excluded_homonym(self):
        self.assertEqual(NONE, classify("기아 타이거즈 우승", STOCKS["000270"]))
        self.assertEqual(DIRECT, classify("기아, 전기차 판매 증가", STOCKS["000270"]))
        self.assertEqual(NONE, classify("Kia 타이거즈 우승", STOCKS["000270"]))   # 제외 단어는 대소문자 무시

    def test_english_words_match_whole_word_only(self):
        kia, sdi = STOCKS["000270"], STOCKS["006400"]
        self.assertEqual(NONE, classify("Nokia, 5G 장비 계약", kia))
        self.assertEqual(NONE, classify("Slovakia 공장 가동", kia))
        self.assertEqual(DIRECT, classify("KIA, 美 판매 신기록", kia))
        self.assertEqual(NONE, classify("Business Express 상장", sdi))
        self.assertEqual(INDUSTRY, classify("ESS 수주 확대", sdi))
        self.assertEqual(INDUSTRY, classify("HBM4 양산 돌입", STOCKS["000660"]))
        self.assertEqual(DIRECT, classify("NAVER웹툰 상장", STOCKS["035420"]))
        self.assertEqual(DIRECT, classify("삼성SDI 전고체 배터리", sdi))


class CollectorTest(unittest.IsolatedAsyncioTestCase):
    async def test_collects_titles_with_rules(self):
        log = []
        c, clock = client(site(log=log))
        store = NewsStore()
        collector = RssCollector(store, c, FEEDS)
        self.assertEqual(2, await collector.run_once())
        self.assertEqual(["/robots.txt", "/rss/stock/", "/rss/"], log)   # 기사 페이지는 요청하지 않음
        self.assertEqual([5.0, 5.0], clock.sleeps)                      # 같은 사이트 5초 간격
        self.assertNotIn("본문", str(store.latest()))

    async def test_etag_304_on_second_run(self):
        seen = []

        def handler(request):
            if request.url.path == "/robots.txt":
                return httpx.Response(200, text=ROBOTS)
            seen.append(request.headers.get("if-none-match"))
            if request.headers.get("if-none-match") == '"v1"':
                return httpx.Response(304)
            return httpx.Response(200, content=FEED, headers={"ETag": '"v1"'})

        c, _ = client(handler)
        collector = RssCollector(NewsStore(), c, FEEDS[:1])
        await collector.run_once()
        await collector.run_once()
        self.assertEqual([None, '"v1"'], seen)
        self.assertIn("304", collector.status.feeds[FEEDS[0][1]])

    async def test_gzip_and_retry(self):
        calls = {"n": 0}

        def handler(request):
            if request.url.path == "/robots.txt":
                return httpx.Response(200, text=ROBOTS)
            calls["n"] += 1
            if calls["n"] == 1:
                return httpx.Response(503)
            return httpx.Response(200, content=gzip.compress(FEED), headers={"Content-Encoding": "gzip"})

        c, clock = client(handler, min_interval=0)
        store = NewsStore()
        await RssCollector(store, c, FEEDS[:1]).run_once()
        self.assertEqual(2, store.size())
        self.assertEqual([2.0], clock.sleeps)

    async def test_robots_block_and_budget(self):
        c, _ = client(site(robots="User-agent: *\nDisallow: /rss/\n"))
        collector = RssCollector(NewsStore(), c, FEEDS)
        await collector.run_once()
        self.assertIn("robots.txt 차단", collector.status.feeds[FEEDS[0][1]])
        c, _ = client(site(), max_requests_per_run=2)
        collector = RssCollector(NewsStore(), c, FEEDS)
        await collector.run_once()
        self.assertEqual(2, c.requests_used)
        self.assertIn("한도", collector.status.feeds[FEEDS[1][1]])


class SummaryTest(unittest.IsolatedAsyncioTestCase):
    async def prepared(self, robots=ROBOTS, page=PAGE, log=None, limiter=None):
        c, clock = client(site(robots=robots, page=page, log=log))
        store = NewsStore()
        await RssCollector(store, c, FEEDS[:1]).run_once()
        return SummaryService(store, c, MockSummarizer(), limiter), store, clock

    def test_extract_body(self):
        body = extract_body(PAGE)
        self.assertIn("HBM 공급 계약", body)
        for unwanted in ("사진 설명", "x()", "hong@example.com", "무단 전재", "푸터", "제목"):
            self.assertNotIn(unwanted, body)

    async def test_one_page_per_request_and_cache(self):
        log = []
        service, store, _ = await self.prepared(log=log)
        result = await service.summarize(f"{SITE}/a/1", "user1")
        self.assertFalse(result["cached"])
        self.assertEqual("출처: 조선비즈", result["attribution"])
        self.assertEqual("2026-11-12T03:00:00+00:00", result["publishedAt"])
        self.assertNotIn("HBM 공급 계약", result["summary"])          # 모의 요약은 본문을 옮기지 않는다
        self.assertTrue((await service.summarize(f"{SITE}/a/1", "user2"))["cached"])
        self.assertEqual(1, log.count("/a/1"))                        # 원문은 한 번만 요청
        self.assertEqual(1, store.summary_count())

    async def test_terms_rechecked_before_request(self):
        log = []
        service, _, clock = await self.prepared(log=log)
        clock.now += 11 * 60                                            # 10분이 지나면 robots.txt를 다시 받는다
        await service.summarize(f"{SITE}/a/1", "user1")
        self.assertEqual(2, log.count("/robots.txt"))

    async def test_terms_changed_stops_summary(self):
        for robots in ("User-agent: *\nContent-Signal: ai-input=no\nAllow: /\n", "User-agent: *\nAllow: /\n"):
            service, _, _ = await self.prepared(robots=robots)
            with self.assertRaises(SummaryError) as error:
                await service.summarize(f"{SITE}/a/1", "user1")
            self.assertEqual("TERMS_CHANGED", error.exception.code)

    async def test_unlisted_link_and_short_body(self):
        service, _, _ = await self.prepared()
        with self.assertRaises(SummaryError) as error:
            await service.summarize("https://evil.example.org/x", "user1")
        self.assertEqual("ARTICLE_NOT_FOUND", error.exception.code)
        service, _, _ = await self.prepared(page="<div itemprop='articleBody'>짧음</div>")
        with self.assertRaises(SummaryError) as error:
            await service.summarize(f"{SITE}/a/1", "user1")
        self.assertEqual("BODY_NOT_FOUND", error.exception.code)

    async def test_usage_limits(self):
        now = datetime(2026, 11, 12, 1, tzinfo=timezone.utc)
        limiter = UsageLimiter(per_user_daily=1, global_hourly=2, now=lambda: now)
        service, _, _ = await self.prepared(limiter=limiter)
        await service.summarize(f"{SITE}/a/1", "user1")
        with self.assertRaises(SummaryError) as error:
            await service.summarize(f"{SITE}/a/2", "user1")
        self.assertEqual("DAILY_LIMIT", error.exception.code)
        await service.summarize(f"{SITE}/a/2", "user2")
        with self.assertRaises(SummaryError) as error:
            limiter.check("user3")
        self.assertEqual("SERVICE_LIMIT", error.exception.code)


class ChatNewsToolTest(unittest.IsolatedAsyncioTestCase):
    async def test_chat_keeps_newest_order_of_related_articles(self):
        feed = {"stockCode": "005930", "fetchedAt": None, "articles": [
            {"title": "무관", "link": "https://x.example.com/1", "relevance": "none"},
            {"title": "산업", "link": "https://x.example.com/2", "relevance": "industry"},
            {"title": "직접", "link": "https://x.example.com/3", "relevance": "direct"}]}
        tool = CompanyNewsTool("http://spring", httpx.MockTransport(lambda r: httpx.Response(200, json=feed)))
        result = await tool.get_company_news(StockContext(symbol="005930", name="삼성전자"))
        self.assertEqual(["산업", "직접"], [a["title"] for a in result["articles"]])   # 뉴스 화면과 같은 순서


class FixesTest(unittest.IsolatedAsyncioTestCase):
    """① 요약 가능 여부 ② robots.txt 확인 실패 구분 ③ 같은 사이트 리다이렉트 ④ 동시 요약 중복 방지"""

    async def collected(self, handler):
        c, clock = client(handler)
        store = NewsStore()
        await RssCollector(store, c, FEEDS[:1]).run_once()
        return store, c, clock

    async def test_summary_available_follows_robots_signal(self):
        from news.summary import summary_available
        for robots, expected in ((ROBOTS, True), ("User-agent: *\nAllow: /\n", False)):
            _, c, _ = await self.collected(site(robots=robots))
            self.assertIs(expected, summary_available(c, f"{SITE}/robots.txt"))
        self.assertFalse(summary_available(PoliteClient(), f"{SITE}/robots.txt"))   # 아직 확인 전이면 숨김

    async def test_robots_server_error_is_not_terms_change(self):
        state = {"robots_fail": False}
        base = site()

        def handler(request):
            if request.url.path == "/robots.txt" and state["robots_fail"]:
                return httpx.Response(500)
            return base(request)

        store, c, clock = await self.collected(handler)
        state["robots_fail"] = True
        clock.now += 11 * 60
        with self.assertRaises(SummaryError) as error:
            await SummaryService(store, c, MockSummarizer()).summarize(f"{SITE}/a/1", "user1")
        self.assertEqual(("TERMS_CHECK_FAILED", 503), (error.exception.code, error.exception.status))

    async def test_redirect_followed_only_within_same_site(self):
        feed = rss(("삼성전자 이동 기사", f"{SITE}/a/r", "Thu, 12 Nov 2026 03:00:00 +0000"),
                   ("외부로 이동", f"{SITE}/a/x", "Thu, 12 Nov 2026 04:00:00 +0000"))
        base = site(feed=feed)

        def handler(request):
            if request.url.path == "/a/r":
                return httpx.Response(301, headers={"Location": "/a/final"})
            if request.url.path == "/a/x":
                return httpx.Response(302, headers={"Location": "https://evil.example.org/a"})
            return base(request)

        store, c, _ = await self.collected(handler)
        service = SummaryService(store, c, MockSummarizer())
        self.assertGreater((await service.summarize(f"{SITE}/a/r", "user1"))["summary"].count("자"), 0)
        with self.assertRaises(ValueError):
            await service.summarize(f"{SITE}/a/x", "user1")

    async def test_concurrent_requests_share_one_summary(self):
        log = []
        store, c, _ = await self.collected(site(log=log))

        class CountingSummarizer(MockSummarizer):
            calls = 0

            async def summarize(self, title, body):
                CountingSummarizer.calls += 1
                await asyncio.sleep(0)
                return await super().summarize(title, body)

        service = SummaryService(store, c, CountingSummarizer())
        results = await asyncio.gather(*(service.summarize(f"{SITE}/a/1", f"user{i}") for i in range(3)))
        self.assertEqual(1, CountingSummarizer.calls)
        self.assertEqual(1, log.count("/a/1"))
        self.assertEqual([False, True, True], [r["cached"] for r in results])


class FeedFilterTest(unittest.IsolatedAsyncioTestCase):
    """뉴스 목록 API: 보관 중인 전체 기사에서 종목 관련 기사만 골라 최신순으로 자른다."""

    async def test_feed_returns_only_related_articles_newest_first(self):
        from app import main
        now = datetime.now(timezone.utc)
        store = NewsStore()
        titles = ["삼성전자 HBM 공급", "프로야구 개막", "반도체 수출 증가", "SK하이닉스 실적", "케이크 예약"]
        store.add([NewsItem(t, f"https://biz.chosun.com/x/{i}/", now - timedelta(hours=i), "조선비즈", now)
                   for i, t in enumerate(titles)], now=now)
        original = main.news_store
        main.news_store = store
        try:
            samsung = await main.news_feed("005930", limit=50)
            hynix = await main.news_feed("000660", limit=1)
        finally:
            main.news_store = original
        self.assertEqual(["삼성전자 HBM 공급", "반도체 수출 증가"], [a["title"] for a in samsung["articles"]])
        self.assertEqual(["직접 관련", "산업 관련"], [a["relevanceLabel"] for a in samsung["articles"]])
        self.assertEqual(5, samsung["periodDays"])
        self.assertEqual(["삼성전자 HBM 공급"], [a["title"] for a in hynix["articles"]])   # HBM은 하이닉스 산업 키워드 → 최신 1건
