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
        for item in feed["articles"]:
            link = item.get("link")
            title = item.get("title")
            if not isinstance(title, str) or not title.strip() or not safe_news_link(link) or link in seen:
                continue
            seen.add(link)
            articles.append({"title": title[:500], "description": str(item.get("description") or "")[:1500],
                             "link": link, "publishedAt": item.get("publishedAt")})
            if len(articles) == 3:
                break
        return {"tool": "get_company_news", "name": stock.name, "symbol": stock.symbol,
                "fetchedAt": feed.get("fetchedAt"), "basis": "뉴스 검색 결과 제목·설명 기준", "articles": articles}
