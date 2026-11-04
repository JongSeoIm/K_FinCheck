import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException, Request

from . import tools
from .config import Settings
from .llm import LlmError, OpenAiProvider
from .schemas import ChatRequest, ChatResponse, ToolRequest, ToolResult
from .spring_client import SpringClient

log = logging.getLogger("ai_server")

TOOLS = {
    "get_stock_quote": lambda spring, body: tools.get_stock_quote(spring, body.company),
    "get_financial_result": lambda spring, body: tools.get_financial_result(spring, body.company, body.year),
    "get_company_news": lambda spring, body: tools.get_company_news(spring, body.company),
    "get_company_info": lambda spring, body: tools.get_company_info(spring, body.company),
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = Settings()
    async with httpx.AsyncClient(base_url=settings.spring_base_url, timeout=settings.tool_timeout_seconds) as http:
        app.state.spring = SpringClient(http)
        app.state.llm = OpenAiProvider(settings.openai_api_key, settings.openai_model, settings.openai_timeout_seconds)
        yield


app = FastAPI(title="K-FinCheck AI 서버", lifespan=lifespan)


@app.get("/health")
async def health(request: Request) -> dict:
    llm = request.app.state.llm
    return {"status": "ok", "model": llm.model, "openaiConfigured": llm.client is not None}


@app.get("/health/model")
async def health_model(request: Request) -> dict:
    try:
        return {"status": "ok", "model": await request.app.state.llm.check_model()}
    except LlmError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error


@app.post("/api/chat")
async def chat(body: ChatRequest, request: Request) -> ChatResponse:
    try:
        answer = await request.app.state.llm.answer(body.message)
    except LlmError as error:
        log.warning("OpenAI 호출 실패: %s", error.__cause__ or error)
        return ChatResponse(status="failed", answer=str(error))
    return ChatResponse(status="answered", answer=answer)


@app.post("/api/tools/{tool_name}")
async def run_tool(tool_name: str, body: ToolRequest, request: Request) -> ToolResult:
    tool = TOOLS.get(tool_name)
    if tool is None:
        raise HTTPException(status_code=404, detail="없는 Tool 입니다.")
    return await tool(request.app.state.spring, body)