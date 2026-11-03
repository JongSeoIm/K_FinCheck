from __future__ import annotations

from collections import defaultdict, deque
from dataclasses import dataclass


@dataclass(frozen=True)
class ChatMessage:
    role: str
    content: str


class ConversationMemory:
    def __init__(self, max_messages: int = 10) -> None:
        self._messages: dict[tuple[int, str], deque[ChatMessage]] = defaultdict(
            lambda: deque(maxlen=max_messages)
        )

    def get(self, user_id: int, conversation_id: str) -> list[ChatMessage]:
        return list(self._messages[(user_id, conversation_id)])

    def append(self, user_id: int, conversation_id: str, message: ChatMessage) -> None:
        self._messages[(user_id, conversation_id)].append(message)

    def clear(self, user_id: int, conversation_id: str) -> None:
        self._messages.pop((user_id, conversation_id), None)
