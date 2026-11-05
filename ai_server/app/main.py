import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException, Request

from .company_tool import get_company_info
from .config import Settings
from .financial_tool import get_financial_result
from .news_tool import get_company_news
from .provider import LlmError, MockProvider, OpenAiProvider
from .quote_tool import get_stock_quote
from .schemas import ChatRequest, ChatResponse, ToolRequest, ToolResult
from .service import ChatService
from .spring_client import SpringClient

logging.basicConfig(level=logging.INFO)

TOOLS = {
    "get_stock_quote": lambda spring, body: get_stock_quote(spring, body.company),
    "get_financial_result": lambda spring, body: get_financial_result(spring, body.company, body.year),
    "get_company_news": lambda spring, body: get_company_news(spring, body.company),
    "get_company_info": lambda spring, body: get_company_info(spring, body.company),
}


def build_provider(settings: Settings):
    if settings.ai_mode == "mock":
        return MockProvider()
    return OpenAiProvider(settings.openai_api_key, settings.openai_model, settings.openai_timeout_seconds)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = Settings()
    async with httpx.AsyncClient(base_url=settings.spring_base_url, timeout=settings.tool_timeout_seconds) as http:
        spring = SpringClient(http)
        provider = build_provider(settings)
        app.state.spring = spring
        app.state.provider = provider
        app.state.chat = ChatService(spring, provider)
        yield


app = FastAPI(title="K-FinCheck AI 서버", lifespan=lifespan)


@app.get("/health")
async def health(request: Request) -> dict:
    provider = request.app.state.provider
    return {"status": "ok", "model": provider.model, "openaiConfigured": provider.client is not None}


@app.get("/health/model")
async def health_model(request: Request) -> dict:
    try:
        return {"status": "ok", "model": await request.app.state.provider.check_model()}
    except LlmError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error


@app.post("/api/chat")
async def chat(body: ChatRequest, request: Request) -> ChatResponse:
    return await request.app.state.chat.ask(body)


@app.post("/api/tools/{tool_name}")
async def run_tool(tool_name: str, body: ToolRequest, request: Request) -> ToolResult:
    tool = TOOLS.get(tool_name)
    if tool is None:
        raise HTTPException(status_code=404, detail="없는 Tool 입니다.")
    return await tool(request.app.state.spring, body)
