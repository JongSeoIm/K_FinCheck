from __future__ import annotations

import unittest

from app.memory import ChatMessage, ConversationMemory
from app.schemas import ChatRequest, StockContext
from app.service import ChatService, OUT_OF_SCOPE_MESSAGE


class RecordingProvider:
    def __init__(self) -> None:
        self.histories: list[list[ChatMessage]] = []

    async def generate(self, history: list[ChatMessage]) -> str:
        self.histories.append(history)
        return "첫 번째 테스트 문장입니다. 두 번째 테스트 문장입니다."


def request(message: str, conversation_id: str = "conversation_001") -> ChatRequest:
    return ChatRequest(
        user_id=1,
        conversation_id=conversation_id,
        message=message,
        stock=StockContext(symbol="005930", name="삼성전자"),
    )


class ChatServiceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.provider = RecordingProvider()
        self.service = ChatService(self.provider, ConversationMemory(max_messages=10))

    async def test_finance_question_is_answered_and_saved(self) -> None:
        response = await self.service.ask(request("삼성전자 실적을 설명해 줘"))

        self.assertEqual("answered", response.status)
        self.assertEqual(2, len(self.service.history(1, "conversation_001")))
        self.assertEqual(1, len(self.provider.histories))

    async def test_out_of_scope_question_does_not_call_provider(self) -> None:
        response = await self.service.ask(request("오늘 저녁 뭐 먹을까?"))

        self.assertEqual("out_of_scope", response.status)
        self.assertEqual(OUT_OF_SCOPE_MESSAGE, response.answer)
        self.assertEqual([], self.provider.histories)
        self.assertEqual([], self.service.history(1, "conversation_001"))

    async def test_only_latest_ten_messages_are_kept(self) -> None:
        for index in range(6):
            await self.service.ask(request(f"주가 질문 {index}"))

        history = self.service.history(1, "conversation_001")
        self.assertEqual(10, len(history))
        self.assertEqual("주가 질문 1", history[0].content)
        self.assertEqual("assistant", history[-1].role)


if __name__ == "__main__":
    unittest.main()
