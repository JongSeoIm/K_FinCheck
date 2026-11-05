from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator

ChatStatus = Literal["answered", "out_of_scope", "needs_clarification", "no_data", "failed"]


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


class StockContext(BaseModel):
    symbol: str = Field(pattern=r"^\d{6}$")
    name: str = Field(min_length=1, max_length=100)


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ConversationContext(BaseModel):
    """직전 답변의 질문 종류·기업·연도. 후속 질문 해석에 쓰며 Spring 이 대화 기록과 함께 보관합니다."""

    category: str | None = None
    company: str | None = None
    year: int | None = None


class QuestionReading(BaseModel):
    """LLM 의 질문 해석 결과. 답변이나 수치는 담지 않습니다."""

    company: str
    category: Literal["quote", "financial", "news", "movement", "outlook", "company", "concept", "unrelated", "clarify"]


class ChatRequest(BaseModel):
    userId: int = Field(gt=0)
    conversationId: str = Field(min_length=8, max_length=100, pattern=r"^[A-Za-z0-9_-]+$")
    message: str = Field(min_length=1, max_length=1500)
    stock: StockContext
    history: list[HistoryMessage] = Field(default_factory=list, max_length=10)
    context: ConversationContext | None = None

    @field_validator("message")
    @classmethod
    def strip_message(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("질문을 입력해 주세요.")
        return value


class ChatResponse(BaseModel):
    status: ChatStatus
    answer: str
    sources: list[Source] = []
    context: ConversationContext | None = None


class ToolRequest(BaseModel):
    company: str = Field(min_length=1, max_length=30)
    year: int | None = Field(default=None, ge=2015, le=2100)
