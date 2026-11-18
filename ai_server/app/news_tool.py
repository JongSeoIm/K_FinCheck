from __future__ import annotations

from urllib.parse import urlsplit
import httpx

from app.schemas import StockContext


def is_news_question(message: str) -> bool:
    return any(word in message for word in ("뉴스", "기사")) and not any(
        word in message.replace(" ", "") for word in ("뉴스란", "기사란", "뉴스가뭐", "기사가뭐")
    )


def safe_news_link(value: str) -> bool:
    try:
        url = urlsplit(value)
        return url.scheme in {"http", "https"} and bool(url.hostname) and url.username is None
    except (ValueError, TypeError):
        return False


class CompanyNewsTool:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    async def get_company_news(self, stock: StockContext) -> dict:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=15, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/news")
            response.raise_for_status()
            feed = response.json()
        if feed["stockCode"] != stock.symbol:
            raise ValueError("뉴스 조회 기업이 일치하지 않습니다.")
        articles = []
        seen = set()
        # 뉴스 화면과 같은 목록(최근 5일, 제목 키워드로 고른 종목 관련 기사, 최신순)의 앞 5건을 쓴다.
        # 번호("1번 기사 요약해줘")가 뉴스 화면 순서와 같도록 순서를 바꾸지 않는다.
        candidates = [item for item in feed["articles"] if item.get("relevance", "direct") in ("direct", "industry")]
        for item in candidates:
            link = item.get("link")
            title = item.get("title")
            if not isinstance(title, str) or not title.strip() or not safe_news_link(link) or link in seen:
                continue
            seen.add(link)
            articles.append({"title": title[:500], "description": str(item.get("description") or "")[:1500],
                             "link": link, "publishedAt": item.get("publishedAt")})
            if len(articles) == 5:
                break
        return {"tool": "get_company_news", "name": stock.name, "symbol": stock.symbol,
                "fetchedAt": feed.get("fetchedAt"), "basis": "조선비즈 RSS 최근 5일 · 제목 키워드 기준", "articles": articles}
