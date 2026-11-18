"""2단계. RSS에서 제목·링크·발행 시각만 꺼낸다.

RSS 응답에는 본문(content:encoded)과 설명(description)이 함께 오지만,
이 모듈은 title·link·pubDate 요소만 읽고 나머지 요소의 내용은 꺼내지 않는다.
"""
from __future__ import annotations

import html
import re
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit
from xml.etree import ElementTree

from news.http import MAX_BYTES
from news.store import NewsItem


_SPACES = re.compile(r"\s+")
# 조선비즈 RSS에는 같은 기사의 일본어판(/jp/)·영어판(/en/)이 섞여 온다. 한국어 기사만 남긴다.
_FOREIGN_PATH = re.compile(r"^/(jp|en)(/|-)")
_KANA = re.compile(r"[぀-ヿ]")


def is_foreign_edition(title: str, link: str) -> bool:
    return bool(_FOREIGN_PATH.match(urlsplit(link).path) or _KANA.search(title))


def clean_title(value: str | None) -> str:
    return _SPACES.sub(" ", html.unescape(value or "")).strip()[:300]


def parse_date(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = parsedate_to_datetime(re.sub(r",(?=\S)", ", ", value.strip()))
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def same_site(link: str, feed_url: str) -> bool:
    try:
        url, feed = urlsplit(link), urlsplit(feed_url)
    except ValueError:
        return False
    return url.scheme == "https" and url.username is None and url.hostname == feed.hostname


def parse_titles(xml_bytes: bytes, feed_url: str, source_name: str, limit: int = 20,
                 now: datetime | None = None) -> list[NewsItem]:
    if len(xml_bytes) > MAX_BYTES:
        raise ValueError("RSS 크기가 너무 큽니다.")
    root = ElementTree.fromstring(xml_bytes)
    now = now or datetime.now(timezone.utc)
    items: list[NewsItem] = []
    seen: set[str] = set()
    for node in root.iterfind("./channel/item"):
        title = clean_title(node.findtext("title"))
        link = (node.findtext("link") or "").strip()
        if not title or link in seen or not same_site(link, feed_url) or is_foreign_edition(title, link):
            continue
        seen.add(link)
        items.append(NewsItem(title=title, link=link, published_at=parse_date(node.findtext("pubDate")),
                              source_name=source_name, collected_at=now))
    items.sort(key=lambda i: i.published_at or now, reverse=True)
    return items[:limit]
