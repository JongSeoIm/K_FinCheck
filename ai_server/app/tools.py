"""조회 Tool. 실제 데이터는 Spring(KIS·DB·OpenDART·NAVER)에서 가져오고, 여기서는 호출만 합니다."""
import asyncio
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx

from .companies import COMPANIES
from .config import settings

KST = timezone(timedelta(hours=9))


@dataclass
class ToolResult:
    tool: str
    code: str
    ok: bool
    facts: str = ""  # LLM 에 줄 정리된 문장
    sources: list[dict] = field(default_factory=list)
    error: str = ""


def company_name(code: str) -> str:
    return COMPANIES.get(code, {}).get("name", code)


def won(value: Any) -> str:
    if value is None:
        return "확인 불가"
    value = int(value)
    return f"{value:,}원"


def big_won(value: Any) -> str:
    """원 단위 큰 금액을 조·억 단위로 읽기 쉽게."""
    if value is None:
        return "확인 불가"
    value = int(value)
    sign = "-" if value < 0 else ""
    eok = abs(value) // 100_000_000
    jo, rest = divmod(eok, 10_000)
    if jo:
        return f"{sign}{jo}조 {rest:,}억 원" if rest else f"{sign}{jo}조 원"
    return f"{sign}{eok:,}억 원"


def kst_text(value: Any) -> str:
    """ISO 문자열 또는 epoch(초)를 'YYYY-MM-DD HH:MM KST' 로."""
    try:
        if value is None or value == "":
            return ""
        if isinstance(value, (int, float)):
            moment = datetime.fromtimestamp(float(value), tz=timezone.utc)
        else:
            moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
            if moment.tzinfo is None:
                moment = moment.replace(tzinfo=timezone.utc)
        return moment.astimezone(KST).strftime("%Y-%m-%d %H:%M KST")
    except (ValueError, OSError, OverflowError):
        return ""


async def _get(client: httpx.AsyncClient, path: str) -> Any:
    headers = {"X-Internal-Key": settings.internal_key} if settings.internal_key else {}
    response = await client.get(f"{settings.spring_base_url}{path}", headers=headers, timeout=settings.tool_timeout)
    response.raise_for_status()
    return response.json()


async def _trade_time(client: httpx.AsyncClient, code: str) -> tuple[str, str] | None:
    """마지막 체결 시각. 실패해도 현재가 답변은 계속하므로 None 을 돌려줍니다."""
    try:
        data = await _get(client, f"/api/kis/trade-time/{code}")
        at = kst_text(data.get("tradedAt"))
        return (at, str(data.get("tradeTime", ""))) if at else None
    except Exception:
        return None


async def get_stock_quote(client: httpx.AsyncClient, code: str) -> ToolResult:
    name = company_name(code)
    try:
        price_data, traded = await asyncio.gather(_get(client, f"/api/kis/price/{code}"), _trade_time(client, code))
        data = price_data
        if not isinstance(data, dict) or data.get("price") is None:
            raise ValueError("시세 형식 오류")
        queried = kst_text(data.get("updatedAt"))
        volume = f"누적거래량 {int(data['volume']):,}주" if isinstance(data.get('volume'), (int, float)) else "누적거래량은 확인하지 못했다"
        change = data.get("change")
        diff = data.get("priceChange")
        if traded:
            basis = f"기준: 최근 체결 시각 {traded[0]}(서버 조회 시각 {queried})."
            basis_at = f"최근 체결 {traded[0]}"
        else:
            basis = f"기준: 서버 조회 시각 {queried}. 최근 체결 시각은 확인하지 못했다."
            basis_at = f"{queried} 조회" if queried else None
        facts = (
            f"[{name} 현재가] 현재가 {won(data['price'])}, 전일 대비 {won(diff)}({change:+.2f}%), "
            f"시가 {won(data.get('open'))}, 고가 {won(data.get('high'))}, 저가 {won(data.get('low'))}, "
            f"전일 종가 {won(data.get('prevClose'))}, {volume}. {basis}"
        )
        source = {"provider": "KIS", "label": f"{name} 현재가", "basisAt": basis_at, "url": None}
        return ToolResult("get_stock_quote", code, True, facts, [source])
    except Exception as exc:  # 네트워크·형식 오류 모두 같은 처리
        return ToolResult("get_stock_quote", code, False, error=type(exc).__name__)


async def get_financial_result(client: httpx.AsyncClient, code: str) -> ToolResult:
    name = company_name(code)
    try:
        data = await _get(client, f"/api/kis/earnings/{code}")
        lines = data.get("lines") or []
        parts = []
        for line in lines:
            current = big_won(line.get("current"))
            previous = big_won(line.get("previous"))
            pct = line.get("changePercent")
            pct_text = f"{pct:+.1f}%" if isinstance(pct, (int, float)) else "증감률 확인 불가"
            status = line.get("status")
            status_text = f", 상태 {status}" if status and status not in ("NORMAL", "OK") else ""
            parts.append(f"{line.get('label')} 당기 {current}, 전기 {previous}, 전기 대비 {pct_text}{status_text}")
        if not parts:
            raise ValueError("실적 없음")
        year, basis = data.get("businessYear"), data.get("basis")
        facts = f"[{name} 실적] {year}년 사업연도 사업보고서, {basis} 기준. " + "; ".join(parts) + "."
        source = {
            "provider": "OpenDART",
            "label": f"{name} {year}년 사업보고서 재무",
            "basisAt": f"{year}년 사업연도 · {basis} · 사업보고서",
            "url": data.get("dartUrl"),
        }
        return ToolResult("get_financial_result", code, True, facts, [source])
    except Exception as exc:
        return ToolResult("get_financial_result", code, False, error=type(exc).__name__)


def _clean(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", text or "")).strip()


async def get_company_news(client: httpx.AsyncClient, code: str) -> ToolResult:
    name = company_name(code)
    try:
        data = await _get(client, f"/api/kis/news/{code}")
        articles = data.get("articles") if isinstance(data, dict) else data
        articles = (articles or [])[:5]
        if not articles:
            raise ValueError("기사 없음")
        lines, sources = [], []
        for index, article in enumerate(articles, 1):
            headline = _clean(article.get("headline"))
            summary = _clean(article.get("summary"))[:160]
            when = kst_text(article.get("datetime"))
            lines.append(f"{index}. {headline} ({when or '게시 시각 확인 불가'}) - {summary}")
            sources.append({"provider": "NAVER API HUB", "label": headline, "basisAt": when or None,
                            "url": article.get("url")})
        facts = f"[{name} 관련 기사 {len(articles)}건, 최신순] " + " / ".join(lines)
        return ToolResult("get_company_news", code, True, facts, sources)
    except Exception as exc:
        return ToolResult("get_company_news", code, False, error=type(exc).__name__)


def get_company_info(code: str) -> ToolResult:
    info = COMPANIES.get(code)
    if not info:
        return ToolResult("get_company_info", code, False, error="unsupported")
    return ToolResult("get_company_info", code, True,
                      f"[기업 정보] 기업명 {info['name']}, 종목 코드 {code}, 시장 {info['market']}.", [])
