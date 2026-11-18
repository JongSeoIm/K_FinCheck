"""조선비즈 요청 규칙: robots.txt 준수, 같은 사이트 5초 간격, ETag, 429·5xx 재시도, 크기 제한."""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Awaitable, Callable
from urllib.parse import urljoin, urlsplit
from urllib.robotparser import RobotFileParser

import httpx


USER_AGENT = "K-FinCheck-StudentProject/0.2 (non-commercial school project; RSS titles, user-requested summaries)"
MAX_BYTES = 2_000_000
RETRY_STATUSES = {429, 500, 502, 503, 504}
ROBOTS_DAILY_SECONDS = 24 * 60 * 60


class RobotsDisallowed(Exception):
    pass


class RobotsUnavailable(Exception):
    """robots.txt를 받지 못했다(서버 오류·연결 실패). 이용 조건이 바뀐 것과 구분한다."""


class BudgetExceeded(Exception):
    pass


MAX_REDIRECTS = 3
REDIRECT_STATUSES = {301, 302, 303, 307, 308}


def parse_content_signal(robots_text: str) -> dict[str, str]:
    """robots.txt의 'User-agent: *' 그룹에 적힌 Content-Signal 값."""
    signals: dict[str, str] = {}
    agents: list[str] = []
    in_rules = False
    for raw in robots_text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if ":" not in line:
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        key = key.lower()
        if key == "user-agent":
            if in_rules:
                agents, in_rules = [], False
            agents.append(value)
            continue
        in_rules = True
        if key == "content-signal" and "*" in agents:
            for pair in value.split(","):
                if "=" in pair:
                    name, setting = (p.strip().lower() for p in pair.split("=", 1))
                    signals[name] = setting
    return signals


@dataclass
class _Robots:
    parser: RobotFileParser | None
    signal: dict[str, str]
    checked_at: float


@dataclass
class FeedResponse:
    status: int
    body: bytes | None


class PoliteClient:
    def __init__(self, transport: httpx.AsyncBaseTransport | None = None, *, min_interval: float = 5.0,
                 max_requests_per_run: int = 30, max_retries: int = 3, backoff_base: float = 2.0,
                 timeout: float = 15.0, sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._transport = transport
        self._min_interval = min_interval
        self._max_requests = max_requests_per_run
        self._max_retries = max_retries
        self._backoff_base = backoff_base
        self._timeout = timeout
        self._sleep = sleep
        self._clock = clock
        self._lock = asyncio.Lock()
        self._last_request: dict[str, float] = {}
        self._etags: dict[str, dict[str, str]] = {}
        self._robots: dict[str, _Robots] = {}
        self._used = 0
        self.log: list[str] = []

    def http(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=self._timeout, transport=self._transport, follow_redirects=False,
                                 headers={"User-Agent": USER_AGENT})

    def start_run(self) -> None:
        self._used = 0

    @property
    def requests_used(self) -> int:
        return self._used

    def content_signal(self, url: str) -> dict[str, str] | None:
        robots = self._robots.get(urlsplit(url).netloc)
        return None if robots is None else dict(robots.signal)

    async def check_robots(self, client: httpx.AsyncClient, url: str, max_age: float = ROBOTS_DAILY_SECONDS,
                           count: bool = True) -> None:
        """max_age초보다 오래된 robots.txt는 다시 받는다. 주소가 차단돼 있으면 RobotsDisallowed."""
        host = urlsplit(url).netloc
        cached = self._robots.get(host)
        if cached is None or self._clock() - cached.checked_at >= max_age:
            try:
                response = await self._get(client, f"{urlsplit(url).scheme}://{host}/robots.txt", {}, count)
            except httpx.TransportError as error:
                raise RobotsUnavailable(f"robots.txt에 연결하지 못했습니다({type(error).__name__}).") from None
            if response.status_code in (404, 410):
                cached = _Robots(None, {}, self._clock())
            elif response.status_code == 200:
                parser = RobotFileParser()
                parser.parse(response.text.splitlines())
                cached = _Robots(parser, parse_content_signal(response.text), self._clock())
            else:
                raise RobotsUnavailable(f"robots.txt를 확인하지 못했습니다({response.status_code}).")
            self._robots[host] = cached
        if cached.parser is not None and not cached.parser.can_fetch(USER_AGENT, url):
            raise RobotsDisallowed("robots.txt가 허용하지 않는 주소입니다.")

    async def fetch_feed(self, client: httpx.AsyncClient, url: str) -> FeedResponse:
        await self.check_robots(client, url)
        response = await self._get(client, url, dict(self._etags.get(url, {})), True)
        if response.status_code == 304:
            return FeedResponse(304, None)
        response.raise_for_status()
        validators = {}
        if etag := response.headers.get("etag"):
            validators["If-None-Match"] = etag
        if modified := response.headers.get("last-modified"):
            validators["If-Modified-Since"] = modified
        self._etags[url] = validators
        return FeedResponse(200, response.content)

    async def fetch_page(self, client: httpx.AsyncClient, url: str) -> str:
        """요약 요청 시 기사 1건만 요청한다(수집 요청 한도와 별도).

        기사 주소가 다른 주소로 넘어가면(리다이렉트) 같은 사이트·https 안에서만 최대 3번 따라간다.
        """
        origin = urlsplit(url).hostname
        for _ in range(MAX_REDIRECTS + 1):
            await self.check_robots(client, url, count=False)
            response = await self._get(client, url, {"Accept": "text/html"}, False)
            if response.status_code not in REDIRECT_STATUSES:
                response.raise_for_status()
                return response.text
            target = urljoin(url, response.headers.get("location", ""))
            parts = urlsplit(target)
            if parts.scheme != "https" or parts.hostname != origin or parts.username is not None:
                raise ValueError("기사 주소가 다른 사이트로 넘어가 요청하지 않았습니다.")
            url = target
        raise ValueError("기사 주소가 너무 여러 번 넘어갑니다.")

    def _interval(self, host: str) -> float:
        robots = self._robots.get(host)
        delay = robots.parser.crawl_delay(USER_AGENT) if robots and robots.parser else None
        return max(self._min_interval, float(delay or 0))

    async def _get(self, client: httpx.AsyncClient, url: str, headers: dict[str, str], count: bool) -> httpx.Response:
        async with self._lock:   # 수집과 요약 요청이 겹쳐도 같은 사이트 간격을 지킨다
            host = urlsplit(url).netloc
            for attempt in range(self._max_retries + 1):
                if count and self._used >= self._max_requests:
                    raise BudgetExceeded("이번 수집의 요청 한도를 다 썼습니다.")
                last = self._last_request.get(host)
                if last is not None and (wait := self._interval(host) - (self._clock() - last)) > 0:
                    await self._sleep(wait)
                if count:
                    self._used += 1
                self._last_request[host] = self._clock()
                self.log.append(url)
                try:
                    response = await self._read(client, url, headers)
                except httpx.TransportError:
                    if attempt == self._max_retries:
                        raise
                    await self._sleep(self._backoff_base * 2 ** attempt)
                    continue
                if response.status_code not in RETRY_STATUSES or attempt == self._max_retries:
                    return response
                await self._sleep(self._retry_after(response) or self._backoff_base * 2 ** attempt)
            raise AssertionError("unreachable")

    @staticmethod
    async def _read(client: httpx.AsyncClient, url: str, headers: dict[str, str]) -> httpx.Response:
        async with client.stream("GET", url, headers=headers) as response:
            chunks, size = [], 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > MAX_BYTES:
                    raise ValueError("응답 크기가 너무 큽니다.")
                chunks.append(chunk)
        # aiter_bytes()는 이미 압축을 푼 데이터이므로 압축·길이 헤더를 빼고 다시 감싼다.
        kept = [(k, v) for k, v in response.headers.multi_items()
                if k.lower() not in {"content-encoding", "content-length", "transfer-encoding"}]
        return httpx.Response(response.status_code, headers=kept, content=b"".join(chunks), request=response.request)

    @staticmethod
    def _retry_after(response: httpx.Response) -> float | None:
        value = (response.headers.get("retry-after") or "").strip()
        if not value:
            return None
        if value.isdigit():
            return min(float(value), 300.0)
        try:
            when = parsedate_to_datetime(value)
        except (TypeError, ValueError):
            return None
        return min(max(0.0, (when - datetime.now(timezone.utc)).total_seconds()), 300.0)
