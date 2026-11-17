import unittest
import httpx

from app.financial_reason import is_reason_question, profit_status
from app.financial_tool import FinancialResultTool, is_financial_question
from app.service import ChatService
from tests.test_service import RecordingProvider, request


def report(code="000660", year=2023, operating="-7730000000000", net="-9137000000000"):
    return {"stockCode": code, "businessYear": year, "previousYear": year - 1,
            "fsDiv": "CFS", "reportCode": "11011", "currency": "KRW",
            "sourceUrl": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=1",
            "metrics": [
                {"label": "매출액", "status": "AVAILABLE", "currentAmount": "327000000000000", "previousAmount": "446000000000000", "growthRate": -26.7, "comparison": "RATE"},
                {"label": "영업이익", "status": "AVAILABLE", "currentAmount": operating, "previousAmount": "6800000000000", "growthRate": None, "comparison": "TURNED_LOSS"},
                {"label": "당기순이익", "status": "AVAILABLE", "currentAmount": net, "previousAmount": "2200000000000", "growthRate": None, "comparison": "TURNED_LOSS"},
            ]}


def service(provider, **kwargs):
    tool = FinancialResultTool("http://spring", httpx.MockTransport(lambda req: httpx.Response(200, json=report(**kwargs))))
    return ChatService(provider, financial_tool=tool)


class FinancialReasonTest(unittest.IsolatedAsyncioTestCase):
    def test_recognizes_loss_words_but_not_concept_questions(self):
        self.assertTrue(is_financial_question("왜 적자야?"))
        self.assertTrue(is_financial_question("하이닉스 흑자 전환했어?"))
        self.assertFalse(is_financial_question("적자가 뭐야?"))
        self.assertTrue(is_reason_question("그럼 왜 적자냐고"))
        self.assertFalse(is_reason_question("2023년 하이닉스 실적 알려줘"))

    def test_profit_status_uses_sign_and_skips_missing(self):
        processed = {"metrics": [{"label": m["label"], "current": str(int(m["currentAmount"]) / 1e8)} for m in report()["metrics"]]}
        status = profit_status(processed)
        self.assertEqual({"영업이익": "적자", "당기순이익": "적자"}, status)
        self.assertEqual({}, profit_status({"metrics": [{"label": "영업이익", "current": None}]}))

    async def test_followup_inherits_company_and_year_and_never_guesses_cause(self):
        provider = RecordingProvider()
        chat = service(provider)
        await chat.ask(request("2023년 하이닉스 실적 알려줘"))
        calls = len(provider.histories)
        result = await chat.ask(request("그럼 왜 적자냐고"))
        self.assertEqual("answered", result.status)
        self.assertIn("SK하이닉스", result.answer)
        self.assertIn("2023년", result.answer)
        self.assertIn("영업이익", result.answer)
        self.assertIn("확인해 드릴 수 없습니다", result.answer)
        self.assertEqual(calls, len(provider.histories))  # 원인 질문은 모델을 호출하지 않는다
        self.assertEqual("financial", result.presentation.type)
        self.assertEqual("OpenDART", result.sources[0].provider)

    async def test_direct_question_with_company_and_year(self):
        provider = RecordingProvider()
        result = await service(provider).ask(request("하이닉스 2023년 왜 적자야?"))
        self.assertIn("적자입니다", result.answer)
        self.assertEqual([], provider.histories)

    async def test_wrong_premise_is_corrected(self):
        provider = RecordingProvider()
        result = await service(provider, operating="1000000000000", net="800000000000").ask(request("하이닉스 2023년 왜 적자야?"))
        self.assertIn("적자가 아니에요", result.answer)

    async def test_plain_loss_question_without_reason_still_uses_model(self):
        provider = RecordingProvider()
        await service(provider).ask(request("하이닉스 2023년 적자야?"))
        self.assertEqual(1, len(provider.histories))

    async def test_fresh_reason_question_is_not_out_of_scope(self):
        provider = RecordingProvider()
        result = await service(provider, code="005930").ask(request("왜 적자야?"))
        self.assertNotEqual("out_of_scope", result.status)
