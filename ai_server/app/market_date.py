"""질문 속 날짜 표현을 실제 거래일로 바꾼다.

GPT 는 오늘 날짜를 모르므로 날짜 계산은 서버가 한다.
- "오늘·지금·현재"  : 장중이면 실시간, 장 시작 전·휴장일이면 직전 거래일 종가, 장 마감 후면 오늘 종가
- "어제·그저께·11월 6일·11/6·2026-11-06" : 그날 종가 (휴장일이면 직전 거래일)
- "지난주·이번 주" : 기간 (공시 조회용)
공시는 거래일이 아니라 달력 날짜로 조회한다(disclosure_period).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo


KST = ZoneInfo("Asia/Seoul")
MARKET_OPEN = time(9, 0)
MARKET_CLOSE = time(15, 30)
# 과거 일봉은 Spring 이 DB(stock_daily_price)에 저장해 둔 것을 날짜로 조회한다. 없으면 Spring 이 KIS 에서 받아 저장한다.
MAX_PAST_DAYS = 365

# 한국거래소 휴장일 (주말 제외). 매년 12월 거래소 공지를 보고 다음 해를 추가한다.
KRX_HOLIDAYS = {
    # 2025
    "2025-01-01", "2025-01-27", "2025-01-28", "2025-01-29", "2025-01-30", "2025-03-03",
    "2025-05-01", "2025-05-05", "2025-05-06", "2025-06-03", "2025-06-06", "2025-08-15",
    "2025-10-03", "2025-10-06", "2025-10-07", "2025-10-08", "2025-10-09", "2025-12-25",
    "2025-12-31",
    # 2026
    "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02", "2026-05-01",
    "2026-05-05", "2026-05-25", "2026-06-03", "2026-07-17", "2026-08-17", "2026-09-24",
    "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25", "2026-12-31",
}


@dataclass(frozen=True)
class DateBasis:
    start: date          # 조회 시작일 (하루 조회면 end 와 같다)
    end: date            # 기준 거래일
    live: bool           # 장중 실시간 시세를 써도 되는지
    label: str           # 답변에 붙일 기준 문구. 예: "2026-11-06(금) 종가 기준"
    note: str | None = None  # 휴장일 보정 등 사용자에게 알릴 내용

    @property
    def is_range(self) -> bool:
        return self.start != self.end


WEEKDAYS = "월화수목금토일"


def now_kst() -> datetime:
    return datetime.now(KST)


def is_trading_day(day: date) -> bool:
    return day.weekday() < 5 and day.isoformat() not in KRX_HOLIDAYS


def previous_trading_day(day: date) -> date:
    """day 를 포함해 가장 가까운 과거 거래일."""
    while not is_trading_day(day):
        day -= timedelta(days=1)
    return day


def day_text(day: date) -> str:
    return f"{day.isoformat()}({WEEKDAYS[day.weekday()]})"


def today_text(now: datetime | None = None) -> str:
    now = now or now_kst()
    return f"{day_text(now.date())} {now.strftime('%H:%M')} (한국시간)"


def _explicit_date(message: str, today: date) -> date | None:
    full = re.search(r"(20\d{2})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})\s*일?", message)
    if full:
        return date(int(full.group(1)), int(full.group(2)), int(full.group(3)))
    month_day = re.search(r"(?<!\d)(\d{1,2})\s*월\s*(\d{1,2})\s*일", message) or \
        re.search(r"(?<![\d/])(\d{1,2})/(\d{1,2})(?![\d/])", message)
    if month_day:
        candidate = date(today.year, int(month_day.group(1)), int(month_day.group(2)))
        # 연도를 생략한 미래 날짜는 작년으로 본다. 예: 1월에 "12월 30일"
        return candidate if candidate <= today else candidate.replace(year=today.year - 1)
    return None


DATE_EXPRESSION = re.compile(r"오늘|금일|지금|현재|어제|전일|그저께|그제|지난\s*주|저번\s*주|이번\s*주|금주"
                             r"|20\d{2}\s*[-./년]\s*\d{1,2}\s*[-./월]\s*\d{1,2}\s*일?|\d{1,2}\s*월\s*\d{1,2}\s*일"
                             r"|(?<![\d/])\d{1,2}/\d{1,2}(?![\d/])")


def has_date_expression(message: str) -> bool:
    return bool(DATE_EXPRESSION.search(message))


# 기업명 찾기에서 지울 날짜 표현. '현재가'·'지금'은 시세 질문 단어라 지우지 않는다.
_STRIP_DATE = re.compile(r"오늘|금일|어제|전일|그저께|그제|지난\s*주|저번\s*주|이번\s*주|금주"
                         r"|20\d{2}\s*[-./년]\s*\d{1,2}\s*[-./월]\s*\d{1,2}\s*일?|\d{1,2}\s*월\s*\d{1,2}\s*일"
                         r"|(?<![\d/])\d{1,2}/\d{1,2}(?![\d/])")


def strip_date_expressions(message: str) -> str:
    """'어제·11월 9일' 같은 날짜 표현을 지운다. 기업명 찾기에서 날짜를 기업명으로 오해하지 않게 한다."""
    return _STRIP_DATE.sub(" ", message)


def resolve_basis(message: str, now: datetime | None = None) -> DateBasis:
    """질문의 날짜 표현을 기준 거래일로 바꾼다. 해석할 수 없거나 범위를 벗어나면 ValueError."""
    now = (now or now_kst()).astimezone(KST)
    today = now.date()
    compact = message.replace(" ", "")

    if re.search(r"지난주|저번주", compact):
        monday = today - timedelta(days=today.weekday() + 7)
        friday = previous_trading_day(monday + timedelta(days=4))
        return DateBasis(monday, friday, False, f"지난주({monday.isoformat()}~{(monday + timedelta(days=4)).isoformat()}) 기준")
    if re.search(r"이번주|금주", compact):
        monday = today - timedelta(days=today.weekday())
        end = previous_trading_day(today) if now.time() >= MARKET_OPEN or not is_trading_day(today) \
            else previous_trading_day(today - timedelta(days=1))
        return DateBasis(monday, max(end, monday), False, f"이번 주({monday.isoformat()}~{today.isoformat()}) 기준")

    try:
        target = _explicit_date(message, today)
    except ValueError as error:
        raise ValueError("날짜를 확인할 수 없습니다. 예: 11월 6일, 2026-11-06") from error
    if target is None:
        if "그저께" in compact or "그제" in compact:
            target = today - timedelta(days=2)
        elif "어제" in compact or "전일" in compact:
            target = today - timedelta(days=1)

    if target is None or target == today:
        return _today_basis(now)
    if target > today:
        raise ValueError("미래 날짜의 시세는 알 수 없습니다. 오늘이나 지난 날짜로 질문해 주세요.")
    if (today - target).days > MAX_PAST_DAYS:
        raise ValueError("과거 시세는 최근 1년까지 조회할 수 있습니다.")
    trading = previous_trading_day(target)
    note = None if trading == target else f"{day_text(target)}은 휴장일이라 직전 거래일인 {day_text(trading)} 기준으로 안내합니다."
    return DateBasis(trading, trading, False, f"{day_text(trading)} 종가 기준", note)


def _today_basis(now: datetime) -> DateBasis:
    today = now.date()
    if is_trading_day(today):
        if MARKET_OPEN <= now.time() < MARKET_CLOSE:
            return DateBasis(today, today, True, f"{today_text(now)} 기준")
        if now.time() >= MARKET_CLOSE:
            return DateBasis(today, today, True, f"{day_text(today)} 종가 기준")
        last = previous_trading_day(today - timedelta(days=1))
        return DateBasis(last, last, False, f"{day_text(last)} 종가 기준",
                         f"아직 장 시작 전이라 직전 거래일인 {day_text(last)} 종가로 안내합니다.")
    last = previous_trading_day(today)
    return DateBasis(last, last, False, f"{day_text(last)} 종가 기준",
                     f"오늘은 휴장일이라 직전 거래일인 {day_text(last)} 종가로 안내합니다.")


def fallback_basis(basis: DateBasis, actual: date) -> DateBasis:
    """그날 일봉이 없어 직전 거래일 일봉을 받았을 때의 기준. 실제 거래일로 바꾸고 안내 문구를 붙인다."""
    if actual == basis.end:
        return basis
    note = f"{day_text(basis.end)}은 거래 기록이 없어 직전 거래일인 {day_text(actual)} 종가로 안내합니다."
    if basis.is_range:
        return DateBasis(basis.start, actual, False, basis.label, note)
    return DateBasis(actual, actual, False, f"{day_text(actual)} 종가 기준", note)


@dataclass(frozen=True)
class DisclosurePeriod:
    start: date
    end: date
    label: str   # 예: "2026-11-06(금) 공시", "최근 30일(2026-10-13~2026-11-11) 공시"


DISCLOSURE_RECENT_DAYS = 30
DISCLOSURE_MAX_DAYS = 31


def disclosure_period(message: str, now: datetime | None = None) -> DisclosurePeriod:
    """공시 조회 기간. 공시는 휴장일에도 나올 수 있어 거래일로 바꾸지 않고 달력 날짜 그대로 쓴다.
    날짜가 없거나 '최근·최신'이면 최근 30일, '오늘'이면 오늘 하루."""
    now = (now or now_kst()).astimezone(KST)
    today = now.date()
    compact = message.replace(" ", "")
    if re.search(r"지난주|저번주", compact):
        monday = today - timedelta(days=today.weekday() + 7)
        sunday = monday + timedelta(days=6)
        return DisclosurePeriod(monday, sunday, f"지난주({monday.isoformat()}~{sunday.isoformat()}) 공시")
    if re.search(r"이번주|금주", compact):
        monday = today - timedelta(days=today.weekday())
        return DisclosurePeriod(monday, today, f"이번 주({monday.isoformat()}~{today.isoformat()}) 공시")
    try:
        target = _explicit_date(message, today)
    except ValueError as error:
        raise ValueError("날짜를 확인할 수 없습니다. 예: 11월 6일, 2026-11-06") from error
    if target is None:
        if "그저께" in compact or "그제" in compact:
            target = today - timedelta(days=2)
        elif "어제" in compact or "전일" in compact:
            target = today - timedelta(days=1)
        elif re.search(r"오늘|금일", compact):
            target = today
    if target is None:
        start = today - timedelta(days=DISCLOSURE_RECENT_DAYS - 1)
        return DisclosurePeriod(start, today, f"최근 {DISCLOSURE_RECENT_DAYS}일({start.isoformat()}~{today.isoformat()}) 공시")
    if target > today:
        raise ValueError("미래 날짜의 공시는 없습니다. 오늘이나 지난 날짜로 질문해 주세요.")
    if (today - target).days > MAX_PAST_DAYS:
        raise ValueError("공시는 최근 1년까지 조회할 수 있습니다.")
    return DisclosurePeriod(target, target, f"{day_text(target)} 공시")
