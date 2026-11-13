import unittest
from datetime import date, datetime

from app.market_date import KST, has_date_expression, is_trading_day, previous_trading_day, resolve_basis


def at(y, m, d, h=10, minute=0):
    return datetime(y, m, d, h, minute, tzinfo=KST)


class MarketDateTest(unittest.TestCase):
    def test_holidays_and_weekends(self):
        self.assertFalse(is_trading_day(date(2026, 11, 8)))   # 일요일
        self.assertFalse(is_trading_day(date(2026, 7, 17)))   # 제헌절(2026 재지정)
        self.assertTrue(is_trading_day(date(2026, 11, 9)))
        self.assertEqual(date(2026, 11, 6), previous_trading_day(date(2026, 11, 8)))

    def test_today_during_market_is_live(self):
        basis = resolve_basis("오늘 삼전 알려줘", at(2026, 11, 9, 10))
        self.assertTrue(basis.live)
        self.assertEqual(date(2026, 11, 9), basis.end)

    def test_today_before_open_uses_previous_trading_close(self):
        # 월요일 08:30 → 직전 거래일은 금요일 11/6 (11/8 일요일이 아님)
        basis = resolve_basis("오늘 삼전 주가", at(2026, 11, 9, 8, 30))
        self.assertFalse(basis.live)
        self.assertEqual(date(2026, 11, 6), basis.end)
        self.assertIn("장 시작 전", basis.note)

    def test_today_after_close_is_today_close(self):
        basis = resolve_basis("오늘", at(2026, 11, 9, 16))
        self.assertEqual(date(2026, 11, 9), basis.end)
        self.assertIn("종가", basis.label)

    def test_holiday_today_and_yesterday_sunday(self):
        self.assertEqual(date(2026, 11, 6), resolve_basis("오늘", at(2026, 11, 8)).end)
        basis = resolve_basis("어제 주가", at(2026, 11, 9))
        self.assertEqual(date(2026, 11, 6), basis.end)
        self.assertIn("휴장일", basis.note)

    def test_explicit_dates(self):
        now = at(2026, 11, 10)
        self.assertEqual(date(2026, 11, 6), resolve_basis("11월 6일 삼성전자", now).end)
        self.assertEqual(date(2026, 11, 6), resolve_basis("11/6 종가", now).end)
        self.assertEqual(date(2026, 11, 6), resolve_basis("2026-11-06 주가", now).end)
        # 연도 없는 미래 날짜는 작년으로 본다. 과거 시세는 DB 저장본 기준 최근 1년까지
        self.assertEqual(date(2025, 12, 30), resolve_basis("12월 30일 주가", now).end)
        with self.assertRaises(ValueError):
            resolve_basis("2025-10-01 주가", now)
        with self.assertRaises(ValueError):
            resolve_basis("2026-11-20 주가", now)

    def test_last_week_is_range(self):
        basis = resolve_basis("지난주 뉴스", at(2026, 11, 10))
        self.assertTrue(basis.is_range)
        self.assertEqual((date(2026, 11, 2), date(2026, 11, 6)), (basis.start, basis.end))

    def test_has_date_expression(self):
        self.assertTrue(has_date_expression("어제 삼전"))
        self.assertTrue(has_date_expression("11월 6일 뉴스"))
        self.assertFalse(has_date_expression("2024년 실적"))
        self.assertFalse(has_date_expression("부채비율이 뭐야"))
