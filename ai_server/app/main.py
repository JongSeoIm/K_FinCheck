from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, HTTPException, Query, status
from openai import OpenAIError
from pydantic import BaseModel, Field

from app.config import Settings, get_settings
from app.memory import ConversationMemory
from app.provider import MockChatProvider, OpenAIChatProvider
from app.schemas import ChatRequest, ChatResponse, HealthResponse
from app.service import ChatService
from app.quote_tool import StockQuoteTool
from app.financial_tool import FinancialResultTool
from app.news_tool import CompanyNewsTool
from news.classify import LABELS, NONE, classify
from news.collector import RssCollector
from news.dictionary import STOCKS
from news.http import PoliteClient
from news.store import NewsStore
from news.summary import (UNAVAILABLE_NOTICE, MockSummarizer, OpenAISummarizer, SummaryError, SummaryService,
                          summary_available)
from news.terms import CHOSUNBIZ, NEWS_DAYS


logger = logging.getLogger(__name__)


def build_service(settings: Settings) -> ChatService:
    if settings.ai_mode == "mock":
        provider = MockChatProvider()
    else:
        if not settings.openai_configured:
            raise RuntimeError("OPENAI_API_KEY와 OPENAI_MODEL 설정이 필요합니다.")
        provider = OpenAIChatProvider(settings.openai_api_key, settings.openai_model)
    return ChatService(provider=provider, memory=ConversationMemory(max_messages=10), quote_tool=StockQuoteTool(settings.spring_base_url), financial_tool=FinancialResultTool(settings.spring_base_url), news_tool=CompanyNewsTool(settings.spring_base_url))


settings = get_settings()
chat_service = build_service(settings)
news_store = NewsStore()
news_client = PoliteClient()
news_collector = RssCollector(news_store, news_client)
news_summaries = SummaryService(news_store, news_client, OpenAISummarizer(settings.openai_api_key, settings.openai_model)
                                if settings.ai_mode == "openai" and settings.openai_configured else MockSummarizer())


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(news_collector.run_forever()) if settings.news_collector_enabled else None
    try:
        yield
    finally:
        if task:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task


app = FastAPI(title="K-FinCheck AI Server", version="0.1.0", lifespan=lifespan)


@app.get("/api/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    configured = settings.ai_mode == "mock" or settings.openai_configured
    return HealthResponse(provider=settings.ai_mode, configured=configured)


@app.post("/api/chat", response_model=ChatResponse)
async def chat(request: ChatRequest) -> ChatResponse:
    try:
        return await chat_service.ask(request)
    except OpenAIError:
        logger.exception("OpenAI API 호출에 실패했습니다.")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="AI 답변을 생성하지 못했습니다.",
        ) from None
    except RuntimeError:
        logger.exception("AI 답변 처리에 실패했습니다.")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AI 서비스를 사용할 수 없습니다.",
        ) from None


@app.get("/api/news/terms")
async def news_terms() -> dict:
    """1단계: 조선비즈 이용 조건과 확인일, 현재 robots.txt의 AI 입력 표시."""
    return {**CHOSUNBIZ.to_dict(), "currentContentSignal": news_client.content_signal(CHOSUNBIZ.robots_url)}


@app.get("/api/news/status")
async def news_status() -> dict:
    s = news_collector.status
    return {"enabled": settings.news_collector_enabled, "running": s.running, "intervalMinutes": s.interval_minutes,
            "lastRunAt": s.last_run_at, "lastRequests": s.last_requests, "storedItems": news_store.size(),
            "cachedSummaries": news_store.summary_count(), "feeds": s.feeds}


@app.get("/api/news/feed")
async def news_feed(stock: str, limit: int = Query(50, ge=1, le=60)) -> dict:
    """6단계: 최근 5일 조선비즈 RSS 기사 중 제목에 종목명·별칭·산업 키워드가 있는 기사만 최신순으로 돌려준다.

    보관 중인 기사 전체에서 먼저 고른 뒤 최신순으로 자른다(관련 없는 기사는 보내지 않음).
    """
    entry = STOCKS.get(stock)
    if entry is None:
        raise HTTPException(status_code=404, detail={"code": "UNSUPPORTED_STOCK",
                                                     "error": "해당 종목의 뉴스는 아직 지원하지 않습니다."})
    articles = []
    for item in news_store.latest(None):
        relevance = classify(item.title, entry)
        if relevance == NONE:
            continue
        articles.append({"title": item.title, "link": item.link, "sourceName": item.source_name,
                         "publishedAt": item.published_at.isoformat() if item.published_at else None,
                         "relevance": relevance, "relevanceLabel": LABELS[relevance]})
        if len(articles) >= limit:
            break
    available = summary_available(news_client)
    return {"stockCode": stock, "companyName": entry.name, "source": CHOSUNBIZ.name,
            "basis": "관련도는 본문 분석이 아닌 제목 키워드 기준입니다.", "periodDays": NEWS_DAYS,
            "summaryAvailable": available, "summaryNotice": "" if available else UNAVAILABLE_NOTICE,
            "fetchedAt": news_collector.status.last_run_at, "articles": articles}


class SummaryRequest(BaseModel):
    link: str = Field(min_length=10, max_length=500)
    userKey: str = Field(min_length=1, max_length=100)


@app.post("/api/news/summary")
async def news_summary(request: SummaryRequest) -> dict:
    """7단계: 사용자가 요청한 기사 1건의 일회성 요약."""
    try:
        return await news_summaries.summarize(request.link, request.userKey)
    except SummaryError as error:
        raise HTTPException(status_code=error.status, detail={"code": error.code, "error": error.message}) from None
    except OpenAIError:
        logger.exception("AI 요약 호출에 실패했습니다.")
        raise HTTPException(status_code=502, detail={"code": "SUMMARY_FAILED", "error": "AI 요약을 만들지 못했습니다."}) from None
    except Exception as error:   # 원문 사이트 연결 실패 등
        logger.warning("기사 요약 실패: %s", type(error).__name__)
        raise HTTPException(status_code=502, detail={"code": "ARTICLE_UNAVAILABLE",
                                                     "error": "원문 기사를 가져오지 못했습니다."}) from None
