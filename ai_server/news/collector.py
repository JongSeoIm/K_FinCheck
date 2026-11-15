"""2단계. 30분 주기 RSS 수집기(피드당 최대 100건, 발행 후 5일 이내 기사만 보관)."""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone

import httpx

from news.http import BudgetExceeded, PoliteClient, RobotsDisallowed, RobotsUnavailable
from news.rss import parse_titles
from news.store import NewsStore
from news.terms import CHOSUNBIZ, FEEDS


logger = logging.getLogger(__name__)


@dataclass
class CollectorStatus:
    running: bool = False
    interval_minutes: int = 30
    last_run_at: str | None = None
    last_requests: int = 0
    feeds: dict[str, str] = field(default_factory=dict)


class RssCollector:
    def __init__(self, store: NewsStore, client: PoliteClient, feeds: tuple[tuple[str, str], ...] = FEEDS,
                 *, items_per_feed: int = 100, interval_minutes: int = 30) -> None:
        self.store = store
        self.client = client
        self._feeds = feeds
        self._items_per_feed = items_per_feed
        self._run_lock = asyncio.Lock()
        self.status = CollectorStatus(interval_minutes=interval_minutes, feeds={url: "대기" for _, url in feeds})

    async def run_once(self) -> int:
        async with self._run_lock:
            self.client.start_run()
            added = 0
            async with self.client.http() as http:
                for category, url in self._feeds:
                    try:
                        response = await self.client.fetch_feed(http, url)
                        if response.status == 304:
                            self.status.feeds[url] = "변경 없음(304)"
                            continue
                        items = parse_titles(response.body or b"", url, CHOSUNBIZ.name, self._items_per_feed)
                        del response   # 본문이 포함된 RSS 원문은 여기서 버린다
                        new = self.store.add(items)
                        added += new
                        self.status.feeds[url] = f"성공({category} {len(items)}건, 새 기사 {new}건)"
                    except BudgetExceeded:
                        self.status.feeds[url] = "요청 한도 초과로 건너뜀"
                        break
                    except RobotsDisallowed as error:
                        self.status.feeds[url] = f"robots.txt 차단: {error}"
                    except RobotsUnavailable as error:
                        self.status.feeds[url] = f"robots.txt 확인 실패로 건너뜀: {error}"
                    except (httpx.HTTPError, ValueError, SyntaxError) as error:
                        logger.warning("RSS 수집 실패 %s: %s", url, type(error).__name__)
                        self.status.feeds[url] = f"실패: {type(error).__name__}"
            self.status.last_run_at = datetime.now(timezone.utc).isoformat()
            self.status.last_requests = self.client.requests_used
            return added

    async def run_forever(self) -> None:
        self.status.running = True
        try:
            while True:
                try:
                    await self.run_once()
                except Exception:   # 수집 오류가 서버를 멈추지 않게 한다
                    logger.exception("RSS 수집 중 예기치 못한 오류")
                await asyncio.sleep(self.status.interval_minutes * 60)
        finally:
            self.status.running = False
