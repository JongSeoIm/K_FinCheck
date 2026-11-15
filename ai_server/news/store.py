"""3단계. 인메모리 임시 보관(DB·파일 기록 없음, 서버를 끄면 사라짐).

- 기사: 제목·링크·발행 시각·출처만, 링크 기준 중복 제거
- 보관 기간: 발행 후 5일(발행 시각이 없으면 수집 후 5일). 지나면 자동 삭제
- 요약문: 중복 요청 방지용으로 3시간만 보관, 기사가 목록에서 지워지면 함께 삭제
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from threading import Lock

from news.terms import NEWS_DAYS


@dataclass(frozen=True)
class NewsItem:
    title: str
    link: str
    published_at: datetime | None
    source_name: str
    collected_at: datetime

    @property
    def dated_at(self) -> datetime:
        """보관 기간·최신순 정렬 기준 시각: 발행 시각, 없으면 수집 시각."""
        return self.published_at or self.collected_at


class NewsStore:
    def __init__(self, ttl: timedelta = timedelta(days=NEWS_DAYS), summary_ttl: timedelta = timedelta(hours=3),
                 max_items: int = 3000) -> None:
        self._ttl = ttl
        self._summary_ttl = summary_ttl
        self._max_items = max_items
        self._items: dict[str, NewsItem] = {}
        self._summaries: dict[str, tuple[datetime, dict]] = {}
        self._lock = Lock()

    def add(self, items: list[NewsItem], now: datetime | None = None) -> int:
        now = now or datetime.now(timezone.utc)
        added = 0
        with self._lock:
            self._purge(now)
            for item in items:
                if now - item.dated_at >= self._ttl:   # 5일보다 오래된 기사는 받지 않는다
                    continue
                if item.link not in self._items:
                    self._items[item.link] = item
                    added += 1
            if len(self._items) > self._max_items:
                for item in sorted(self._items.values(), key=lambda i: i.dated_at)[: len(self._items) - self._max_items]:
                    self._remove(item.link)
        return added

    def get(self, link: str, now: datetime | None = None) -> NewsItem | None:
        with self._lock:
            self._purge(now or datetime.now(timezone.utc))
            return self._items.get(link)

    def latest(self, limit: int | None = 40, now: datetime | None = None) -> list[NewsItem]:
        """최신순 목록. limit=None이면 보관 중인 기사 전부."""
        now = now or datetime.now(timezone.utc)
        with self._lock:
            self._purge(now)
            items = list(self._items.values())
        items.sort(key=lambda i: i.dated_at, reverse=True)
        return items if limit is None else items[: max(0, limit)]

    def size(self, now: datetime | None = None) -> int:
        with self._lock:
            self._purge(now or datetime.now(timezone.utc))
            return len(self._items)

    def cached_summary(self, link: str, now: datetime | None = None) -> dict | None:
        with self._lock:
            self._purge(now or datetime.now(timezone.utc))
            entry = self._summaries.get(link)
            return entry[1] if entry else None

    def keep_summary(self, link: str, summary: dict, now: datetime | None = None) -> None:
        now = now or datetime.now(timezone.utc)
        with self._lock:
            if link in self._items:
                self._summaries[link] = (now, summary)

    def summary_count(self, now: datetime | None = None) -> int:
        with self._lock:
            self._purge(now or datetime.now(timezone.utc))
            return len(self._summaries)

    def _remove(self, link: str) -> None:
        self._items.pop(link, None)
        self._summaries.pop(link, None)

    def _purge(self, now: datetime) -> None:
        for link in [l for l, item in self._items.items() if now - item.dated_at >= self._ttl]:
            self._remove(link)
        for link in [l for l, (at, _) in self._summaries.items() if now - at >= self._summary_ttl]:
            del self._summaries[link]
