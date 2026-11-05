"""사용자·대화별 최근 메시지. 서버 메모리에만 두며 재시작하면 사라집니다."""

import time
from collections import deque
from dataclasses import dataclass, field


@dataclass
class Message:
    role: str
    content: str


@dataclass
class Conversation:
    messages: deque
    updated_at: float = 0
    last_category: str | None = None
    last_company: str | None = None
    last_year: int | None = None
    context: dict = field(default_factory=dict)


class ConversationMemory:
    def __init__(self, max_messages: int = 10, ttl_minutes: int = 30, clock=time.monotonic):
        self.max_messages = max_messages
        self.ttl_seconds = ttl_minutes * 60
        self.clock = clock
        self._items: dict[tuple[int, str], Conversation] = {}

    def _expire(self) -> None:
        now = self.clock()
        for key in [k for k, c in self._items.items() if now - c.updated_at >= self.ttl_seconds]:
            del self._items[key]

    def get(self, user_id: int, conversation_id: str) -> Conversation:
        self._expire()
        key = (user_id, conversation_id)
        if key not in self._items:
            self._items[key] = Conversation(deque(maxlen=self.max_messages), self.clock())
        return self._items[key]

    def add(self, user_id: int, conversation_id: str, question: str, answer: str) -> None:
        conversation = self.get(user_id, conversation_id)
        conversation.messages.append(Message("user", question))
        conversation.messages.append(Message("assistant", answer))
        conversation.updated_at = self.clock()

    def clear(self, user_id: int, conversation_id: str | None = None) -> None:
        for key in list(self._items):
            if key[0] == user_id and (conversation_id is None or key[1] == conversation_id):
                del self._items[key]
