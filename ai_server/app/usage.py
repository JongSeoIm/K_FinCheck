"""질문 1건에서 쓴 OpenAI 토큰을 모은다. Spring 이 ai_usage_log 에 저장한다."""
from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass, field


@dataclass
class Usage:
    models: list[str] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0
    embedding_tokens: int = 0
    llm_calls: int = 0

    def add_response(self, model: str, usage) -> None:
        self.llm_calls += 1
        if model not in self.models:
            self.models.append(model)
        if usage is not None:
            self.input_tokens += int(getattr(usage, "input_tokens", 0) or 0)
            self.output_tokens += int(getattr(usage, "output_tokens", 0) or 0)

    def add_embedding(self, model: str, tokens: int) -> None:
        if model not in self.models:
            self.models.append(model)
        self.embedding_tokens += int(tokens or 0)

    def as_dict(self) -> dict:
        return {"model": ",".join(self.models) or None, "input_tokens": self.input_tokens,
                "output_tokens": self.output_tokens, "embedding_tokens": self.embedding_tokens,
                "llm_calls": self.llm_calls}


_current: ContextVar[Usage | None] = ContextVar("usage", default=None)


def start_usage() -> Usage:
    usage = Usage()
    _current.set(usage)
    return usage


def current_usage() -> Usage:
    usage = _current.get()
    if usage is None:
        usage = start_usage()
    return usage
