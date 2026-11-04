"""질문 분류 규칙. Tool 선택은 이 규칙이 하고, OpenAI 는 선택에 관여하지 않습니다."""
import re
from dataclasses import dataclass, field

from .companies import find_companies, find_unsupported

FINANCE_WORDS = [
    "주가", "주식", "현재가", "시세", "등락", "실적", "매출", "영업이익", "순이익", "재무", "공시", "뉴스", "기사",
    "배당", "per", "pbr", "roe", "eps", "부채", "유동비율", "이익률", "시가총액", "코스피", "코스닥", "투자",
    "증시", "상장", "종목", "etf", "금리", "환율", "채권", "펀드", "공매도", "거래량", "목표가", "매수", "매도",
    "자본", "자산", "수익", "손실", "적자", "흑자", "재무제표", "증권", "분산투자", "인플레이션", "주주", "배당금",
    "차트", "캔들", "호가", "손절", "물타기", "상한가", "하한가", "시장", "경제", "금융", "은행", "대출", "이자",
]
FOLLOW_UP_RE = re.compile(r"^(그럼|그러면|그건|그게|그거|이건|이게|그래서|왜|어째서|더|좀 더|다시|그리고)")

QUOTE_WORDS = ["현재가", "시세", "얼마", "주가", "등락", "몇 원", "몇원", "올랐", "내렸", "떨어"]
EARNINGS_WORDS = ["실적", "매출", "영업이익", "순이익", "당기순이익", "재무", "적자", "흑자"]
NEWS_WORDS = ["뉴스", "기사", "소식", "이슈"]
MOVE_RE = re.compile(r"(왜|이유|원인|때문).*(올랐|내렸|떨어|상승|하락|급등|급락|오른|내린|올라|내려)|(올랐|내렸|떨어|상승|하락|급등|급락|올라|내려).*(왜|이유|원인|때문)")
OUTLOOK_WORDS = ["전망", "살까", "팔까", "사도", "팔아도", "오를까", "내릴까", "투자해도", "매수해", "매도해", "추천"]
COMPARE_WORDS = ["비교", "vs", "랑 ", "와 ", "과 ", "중에"]
CONCEPT_RE = re.compile(r"(뭐야|뭔가요|무엇|무슨 뜻|란\?|이란|설명|어떻게 계산|차이|의미|뜻이|어떻게 읽|읽는|해석|높다는|낮다는|어떻게 달라)")


@dataclass
class Plan:
    kind: str  # concept | data | out_of_scope | need_info | unsupported
    tools: list[str] = field(default_factory=list)  # quote | earnings | news
    codes: list[str] = field(default_factory=list)
    missing: str = ""
    unsupported: str = ""
    intent: str = ""


def _has(text: str, words: list[str]) -> bool:
    lowered = text.lower()
    return any(w in lowered for w in words)


def classify(question: str, selected_code: str | None = None, has_history: bool = False) -> Plan:
    text = question.strip()
    codes = find_companies(text)
    unsupported = find_unsupported(text)
    finance = _has(text, FINANCE_WORDS)

    if unsupported and not codes:
        return Plan(kind="unsupported", unsupported=unsupported)

    follow_up = has_history and bool(FOLLOW_UP_RE.match(text)) and len(text) <= 40
    if not finance and not codes and not follow_up:
        return Plan(kind="out_of_scope")

    wants_quote = _has(text, QUOTE_WORDS)
    wants_earnings = _has(text, EARNINGS_WORDS)
    wants_news = _has(text, NEWS_WORDS)
    wants_move = bool(MOVE_RE.search(text))
    wants_outlook = _has(text, OUTLOOK_WORDS)

    tools: list[str] = []
    intent = ""
    if wants_move:
        tools = ["quote", "news"] + (["earnings"] if wants_earnings else [])
        intent = "move"
    elif wants_outlook:
        tools = ["quote", "earnings", "news"]
        intent = "outlook"
    else:
        if wants_quote:
            tools.append("quote")
        if wants_earnings:
            tools.append("earnings")
        if wants_news:
            tools.append("news")
        intent = "data" if tools else ""

    # 종목이 있는데 무엇을 알고 싶은지 모르면 다시 묻습니다.
    if codes and not tools and not CONCEPT_RE.search(text):
        if len(codes) >= 2:
            return Plan(kind="need_info", missing="비교할 지표(현재가, 실적, 뉴스 중)")
        return Plan(kind="need_info", missing="알고 싶은 내용(현재가, 실적, 관련 기사 중)")

    # 개념 질문: 조회할 자료가 없거나, 종목 없이 '뭐야/뜻' 같은 표현만 있는 경우
    if not tools or (CONCEPT_RE.search(text) and not codes):
        return Plan(kind="concept", intent="concept")

    # 기업 정하기: 질문에 나온 기업 → 없으면 지금 보고 있는 종목
    if not codes:
        if selected_code:
            codes = [selected_code]
        else:
            return Plan(kind="need_info", missing="기업명이나 종목 코드")

    return Plan(kind="data", tools=tools, codes=codes[:3], intent=intent)
