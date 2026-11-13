from __future__ import annotations

from urllib.parse import urlsplit
import httpx

from app.schemas import StockContext


POLICY_TERMS = (
    "정책", "산업 동향", "산업동향", "규제", "지원책", "지원 정책", "지원정책",
    "보조금", "세액공제", "관세", "수출 통제", "수출통제", "산업 지원",
)


def is_policy_question(message: str) -> bool:
    compact = message.replace(" ", "")
    return any(term.replace(" ", "") in compact for term in POLICY_TERMS)


def safe_policy_link(value: str) -> bool:
    try:
        url = urlsplit(value)
        if url.scheme != "https" or url.username is not None:
            return False
        if url.hostname == "www.motir.go.kr":
            return url.path.startswith("/kor/article/ATCL3f49a5a8c/") and url.path.endswith("/view")
        return (url.hostname == "www.korea.kr" and url.path == "/news/policyNewsView.do"
                and any(part.startswith("newsId=") for part in url.query.split("&")))
    except (ValueError, TypeError):
        return False


class IndustryPolicyTool:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    async def get_industry_policies(self, stock: StockContext) -> dict:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=20, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/policies")
            response.raise_for_status()
            feed = response.json()
        if feed["stockCode"] != stock.symbol:
            raise ValueError("산업·정책 자료의 대상 기업이 일치하지 않습니다.")
        documents = []
        seen = set()
        for item in feed["policies"]:
            link = item.get("link")
            title = item.get("title")
            score = item.get("relevanceScore")
            if (not isinstance(title, str) or not title.strip() or not safe_policy_link(link)
                    or link in seen or not isinstance(score, int) or score < 50):
                continue
            seen.add(link)
            documents.append({
                "title": title[:500],
                "summary": str(item.get("summary") or "")[:1000],
                "content": str(item.get("content") or "")[:4000],
                "link": link,
                "publishedAt": item.get("publishedAt"),
                "relationType": item.get("relationType"),
                "relevanceScore": score,
                "policyStatus": item.get("policyStatus"),
                "source": item.get("source"),
                "publisher": item.get("publisher"),
                "licenseType": item.get("licenseType"),
                "tags": [str(tag)[:30] for tag in item.get("tags", [])[:3] if isinstance(tag, str)],
            })
            if len(documents) == 3:
                break
        return {
            "tool": "get_industry_policies",
            "name": stock.name,
            "symbol": stock.symbol,
            "fetchedAt": feed.get("fetchedAt"),
            "basis": "산업통상부·대한민국 정책브리핑 공식 자료",
            "documents": documents,
        }
