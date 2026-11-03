from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException, status
from openai import OpenAIError

from app.config import Settings, get_settings
from app.memory import ConversationMemory
from app.provider import MockChatProvider, OpenAIChatProvider
from app.schemas import ChatRequest, ChatResponse, HealthResponse
from app.service import ChatService
from app.quote_tool import StockQuoteTool
from app.financial_tool import FinancialResultTool
from app.news_tool import CompanyNewsTool


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
app = FastAPI(title="K-FinCheck AI Server", version="0.1.0")


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
