from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator


class StockContext(BaseModel):
    symbol: str = Field(pattern=r"^\d{6}$")
    name: str = Field(min_length=1, max_length=100)


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=20000)


class ChatRequest(BaseModel):
    user_id: int = Field(gt=0)
    conversation_id: str = Field(min_length=8, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")
    message: str = Field(min_length=1, max_length=1500)
    stock: StockContext
    history: list[HistoryMessage] | None = Field(default=None, max_length=10)

    @field_validator("message")
    @classmethod
    def normalize_message(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("질문을 입력해 주세요.")
        return normalized


class SourceReference(BaseModel):
    provider: Literal["KIS", "OpenDART", "NAVER"]
    label: str
    basis_at: str | None = None
    url: str | None = None


class AnswerPresentation(BaseModel):
    type: Literal["quote", "financial", "financial_comparison", "news", "composite"]
    data: dict


class ChatResponse(BaseModel):
    status: Literal["answered", "out_of_scope"]
    answer: str
    sources: list[SourceReference] = Field(default_factory=list)
    presentation: AnswerPresentation | None = None
    context_message: str | None = None


class HealthResponse(BaseModel):
    status: Literal["UP"] = "UP"
    provider: Literal["mock", "openai"]
    configured: bool
