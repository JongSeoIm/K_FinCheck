import unittest

from app.question_context import prepare_question
from app.schemas import HistoryMessage, StockContext


class ReasonFollowupMetricTest(unittest.TestCase):
    def test_metric_reason_question_inherits_company_and_year(self):
        previous = [HistoryMessage(role="user", content="2023년 삼성전자 실적 알려줘")]
        selected = StockContext(symbol="000660", name="SK하이닉스")
        for question in ("왜 영업이익이 줄었어?", "왜 매출이 줄었어?", "근데 순이익은 왜 이래?", "영업이익은 왜 줄었어?"):
            message, stock = prepare_question(question, previous, selected)
            self.assertEqual("005930", stock.symbol, question)
            self.assertIn("삼성전자", message)
            self.assertIn("2023", message)

    def test_unknown_company_is_still_rejected(self):
        selected = StockContext(symbol="000660", name="SK하이닉스")
        with self.assertRaises(ValueError):
            prepare_question("테슬라 영업이익 알려줘", [], selected)


if __name__ == "__main__":
    unittest.main()
