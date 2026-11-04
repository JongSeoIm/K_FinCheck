import logging

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from . import llm
from .chat import handle
from .config import settings

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("ai_server")

app = FastAPI(title="K_FinCheck AI Server", docs_url=None, redoc_url=None, openapi_url=None)
_client: httpx.AsyncClient | None = None


def client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient()
    return _client


class Message(BaseModel):
    role: str
    content: str = Field(max_length=2000)


class ChatRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    history: list[Message] = Field(default_factory=list, max_length=20)
    stockCode: str | None = Field(default=None, max_length=6)


def _check_key(key: str | None) -> None:
    if settings.internal_key and key != settings.internal_key:
        raise HTTPException(status_code=403, detail="forbidden")


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/health/model")
async def health_model(x_internal_key: str | None = Header(default=None)):
    _check_key(x_internal_key)
    return await llm.check_model(client())


@app.post("/chat")
async def chat(body: ChatRequest, x_internal_key: str | None = Header(default=None)):
    _check_key(x_internal_key)
    outcome = await handle(
        body.question,
        [m.model_dump() for m in body.history],
        body.stockCode,
        client(),
        llm.complete,
    )
    # 질문 내용은 로그에 남기지 않고 결과 종류만 남깁니다.
    log.info("chat status=%s sources=%d", outcome.status, len(outcome.sources))
    return {"status": outcome.status, "answer": outcome.answer, "sources": outcome.sources}
