import unittest

from app.question_interpreter import QuestionInterpretation, validated_question
from app.schemas import StockContext
from app.service import ChatService
from tests.test_service import RecordingProvider, request
from tests.test_composite import QuoteTool, FinancialTool


def interpretation(**values):
    return QuestionInterpretation.model_validate({
        "company": "삼성전자", "intent": "quote", "needs_clarification": False,
        "period": "current", "criteria": "unspecified", **values})


class InterpretingProvider(RecordingProvider):
    def __init__(self, result=None):
        super().__init__()
        self.result = result or interpretation()
        self.interpretations = []

    async def interpret(self, message, previous, selected):
        self.interpretations.append(message)
        return self.result


class InterpreterTest(unittest.IsolatedAsyncioTestCase):
    async def test_alias_nouns_and_common_typos_skip_interpretation(self):
        provider = InterpretingProvider()
        service = ChatService(provider, quote_tool=QuoteTool(), financial_tool=FinancialTool())
        for text, expected in [("삼전 현재가", "quote"), ("삼전 주까", "quote"), ("하닉 실적", "financial")]:
            response = await service.ask(request(text))
            self.assertEqual(expected, response.presentation.type)
        self.assertEqual([], provider.interpretations)

    async def test_unknown_expression_uses_interpretation_once(self):
        provider = InterpretingProvider()
        response = await ChatService(provider, quote_tool=QuoteTool()).ask(request("삼성젼자 주가"))
        self.assertEqual("005930", response.presentation.data["symbol"])
        self.assertEqual(1, len(provider.interpretations))

    async def test_ambiguous_company_and_model_uncertainty_skip_generation(self):
        provider = InterpretingProvider(interpretation(needs_clarification=True))
        service = ChatService(provider, quote_tool=QuoteTool())
        for text in ["삼성", "삼성젼자 주가"]:
            response = await service.ask(request(text))
            self.assertIsNone(response.presentation)
        self.assertEqual([], provider.histories)

    async def test_interpreter_failure_asks_instead_of_guessing(self):
        class FailedProvider(InterpretingProvider):
            async def interpret(self, *args):
                raise ValueError("parse failed")
        provider = FailedProvider()
        result = await ChatService(provider, quote_tool=QuoteTool()).ask(request("삼성젼자 주가"))
        self.assertIsNone(result.presentation)
        self.assertEqual([], provider.histories)

    async def test_unrelated_question_does_not_use_interpreter_after_chat(self):
        provider = InterpretingProvider()
        service = ChatService(provider, quote_tool=QuoteTool())
        await service.ask(request("삼전 현재가"))
        result = await service.ask(request("오늘 저녁 뭐 먹을까?"))
        self.assertEqual("out_of_scope", result.status)
        self.assertEqual([], provider.interpretations)

    def test_server_rejects_conflicting_company_period_and_unknown(self):
        selected = StockContext(symbol="005930", name="삼성전자")
        for result, original in [(interpretation(company="NAVER"), "하닉 주가"),
                                 (interpretation(company="unknown"), "애플 가격"),
                                 (interpretation(company="selected"), "애플 현재가"),
                                 (interpretation(company="삼성전자"), "애플 현재가"),
                                 (interpretation(), "지난주 삼성젼자 주가"),
                                 (interpretation(intent="financial"), "삼성젼자 별도 실적")]:
            with self.assertRaises(ValueError):
                validated_question(result, original, selected)
