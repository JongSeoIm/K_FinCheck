from typing import Any, Literal

from pydantic import BaseModel, Field


class Source(BaseModel):
    provider: str
    label: str
    basisAt: str | None = None
    url: str | None = None


class ToolResult(BaseModel):
    tool: str
    ok: bool
    data: Any = None
    sources: list[Source] = []
    error: str | None = None


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)


class ChatResponse(BaseModel):
    status: Literal["answered", "failed"]
    answer: str
    sources: list[Source] = []


class ToolRequest(BaseModel):
    company: str = Field(min_length=1, max_length=30)
    year: int | None = Field(default=None, ge=2015, le=2100)