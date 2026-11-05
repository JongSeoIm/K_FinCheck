"""질문 분류 규칙. 어떤 Tool 을 쓸지는 이 규칙이 정하고, OpenAI 는 Tool 선택에 관여하지 않습니다."""

import re
from dataclasses import dataclass, field
from datetime import datetime
from difflib import SequenceMatcher

from .formatters import KST
from .question_context import TARGET_WORDS, compact, find_companies, partial_company, unknown_company

QUOTE_WORDS = ("현재가", "주가", "시세", "등락", "거래량", "얼마", "가격")
FINANCIAL_WORDS = ("실적", "매출", "영업이익", "순이익", "당기순이익", "재무", "사업보고서")
NEWS_WORDS = ("뉴스", "기사", "소식", "공시내용")
MOVE_WORDS = ("왜올랐", "왜내렸", "왜떨어", "왜상승", "왜하락", "왜급등", "왜급락", "오른이유", "내린이유", "떨어진이유", "상승이유", "하락이유", "급등이유", "급락이유")
OUTLOOK_WORDS = ("전망", "사도", "팔아도", "살까", "팔까", "매수해", "매도해", "오를까", "내릴까", "떨어질까", "목표가")
COMPANY_WORDS = ("어떤회사", "무슨회사", "뭐하는회사", "어떤기업", "무슨기업", "뭐하는기업", "회사정보", "기업정보", "회사소개", "기업소개", "업종", "종목코드")
CONCEPT_WORDS = ("뭐야", "뭔가", "뜻", "의미", "정의", "설명해", "무슨말", "어떻게계산", "계산법", "계산방법", "차이", "이란", "란뭐")
COMPARE_WORDS = ("비교", "어디가더", "뭐가더", "누가더")
METRIC_WORDS = ("현재가", "주가", "시세", "실적", "매출", "영업이익", "순이익")
HISTORY_WORDS = ("어제", "지난주", "지난달", "작년주가", "과거주가", "한달전", "일년전")
UNSUPPORTED_FINANCIAL_WORDS = ("분기", "반기", "별도", "예상실적", "컨센서스")
FOLLOW_UP_START = re.compile(r"^(그럼|그러면|그건|그게|그거|이건|이게|그래서|그리고|또|다시|더|좀더)")
REFERENCE_WORDS = ("거기", "그회사", "그기업", "그종목")
ALSO_ASKED = re.compile(rf"({TARGET_WORDS})도")  # '실적도 알려줘'
FOLLOW_UP_WORDS = ("전년", "작년", "재작년", "대비", "증감", "이유", "자세히", "요약", "다른", "그회사", "거기")
FINANCE_TERMS = (
    "per", "pbr", "roe", "roa", "eps", "ebitda", "부채", "유동비율", "자본", "자산", "이익률", "시가총액", "배당", "주식",
    "채권", "펀드", "etf", "코스피", "코스닥", "증시", "상장", "공매도", "금리", "환율", "인플레이션", "재무제표", "손익계산서",
    "현금흐름", "영업이익", "순이익", "매출", "캔들", "차트", "호가", "상한가", "하한가", "투자", "주주", "증권", "종목",
    "증자", "액면", "시총", "공시", "사업보고서", "코픽스", "리츠", "예금", "적금", "대출", "이자",
)
YEAR_PATTERN = re.compile(r"(20\d{2})\s*년?")

LENGTHS = {
    "company": "1~3문장으로 조회 결과의 종목 코드, 시장, 업종만 알려 줍니다.",
    "concept": "2~4문장으로 정의, 필요한 계산 방식, 짧은 예시 순서로 설명합니다.",
    "quote": "2~5문장으로 현재 수치와 기준 시각을 알려 줍니다.",
    "financial": "3~6문장으로 수치, 전년 대비, 사업연도, 연결 기준을 알려 줍니다.",
    "news": "3~6문장으로 기사 제목과 검색 결과 설명을 바탕으로 핵심만 정리합니다. 기사 전문을 읽은 것처럼 말하지 않습니다.",
    "movement": "4~8문장으로 실제 등락과 확인된 기사·실적을 말하고, 확인된 사실과 가능한 해석을 구분합니다. 원인을 단정하지 않습니다.",
    "outlook": "단정하지 않고 확인된 자료와 판단할 때 주의할 점을 설명합니다. 매수나 매도를 지시하지 않습니다.",
    "comparison": "4~8문장으로 조회한 지표만 비교합니다.",
}


@dataclass
class Interpretation:
    category: str
    companies: list[str] = field(default_factory=list)
    tools: list[str] = field(default_factory=list)
    year: int | None = None
    reply: str | None = None  # LLM 없이 서버 문구로 답할 때
    reply_status: str = "out_of_scope"
    ask_llm: bool = False  # 규칙이 애매해서 LLM 해석을 먼저 해 볼 때. 실패하면 reply 로 답합니다
    unknown: str | None = None  # 지원 목록에 없는 이름. 전체 상장사에서 다시 찾습니다
    wanted: str | None = None  # unknown 을 찾았을 때 쓸 질문 종류


def has(text: str, words: tuple[str, ...]) -> bool:
    return any(word in text for word in words)


def category_of(text: str, named: list[str]) -> str | None:
    if has(text, COMPARE_WORDS) or len(named) > 1:
        return "comparison"
    if has(text, MOVE_WORDS):
        return "movement"
    if has(text, OUTLOOK_WORDS) or ("추천" in text and (named or has(text, FINANCE_TERMS))):
        return "outlook"
    if has(text, COMPANY_WORDS):
        return "company"
    if has(text, CONCEPT_WORDS) and not named and (has(text, FINANCE_TERMS) or has(text, FINANCIAL_WORDS) or has(text, QUOTE_WORDS)):
        return "concept"
    if has(text, NEWS_WORDS):
        return "news"
    if has(text, FINANCIAL_WORDS):
        return "financial"
    if has(text, QUOTE_WORDS):
        return "quote"
    if has(text, FINANCE_TERMS):
        return "concept"
    return None


def tools_for(category: str, text: str) -> list[str]:
    if category == "company":
        return ["get_company_info"]
    if category == "quote":
        return ["get_stock_quote"]
    if category == "financial":
        return ["get_financial_result"]
    if category == "news":
        return ["get_company_news"]
    if category == "movement":
        return ["get_stock_quote", "get_company_news"] + (["get_financial_result"] if has(text, FINANCIAL_WORDS) else [])
    if category == "outlook":
        return ["get_stock_quote", "get_financial_result", "get_company_news"]
    if category == "comparison":
        tools = []
        if has(text, QUOTE_WORDS):
            tools.append("get_stock_quote")
        if has(text, FINANCIAL_WORDS):
            tools.append("get_financial_result")
        return tools
    return []


def relative_year(text: str) -> int | None:
    """사업보고서 기준의 상대 연도. 전년 대비처럼 비교를 뜻하는 말은 연도로 보지 않습니다."""
    today = datetime.now(KST).year
    if "재작년" in text:
        return today - 2
    if "작년" in text:
        return today - 1
    return None


def clarify(reply: str) -> Interpretation:
    return Interpretation("clarify", reply=reply, reply_status="needs_clarification")


def unsupported_company(name: str) -> Interpretation:
    return Interpretation("unsupported", reply=f"{name}은(는) 국내 상장사 목록에서 찾지 못했습니다. 국내 상장 기업의 정확한 이름으로 질문해 주세요.", reply_status="no_data")


def finish(category: str, named: list[str], text: str, year: int | None, selected: str) -> Interpretation:
    """질문 종류와 기업이 정해진 뒤의 공통 검사와 Tool 선택. 규칙과 LLM 해석이 함께 씁니다."""
    if category in ("financial", "outlook", "comparison") and has(text, UNSUPPORTED_FINANCIAL_WORDS):
        return Interpretation("unsupported", reply="현재는 연결 사업보고서 기준의 연간 실적만 확인할 수 있습니다. 분기·반기·별도 실적과 예상치는 제공하지 않습니다.", reply_status="no_data")
    if category in ("quote", "news", "movement") and has(text, HISTORY_WORDS):
        return Interpretation("unsupported", reply="현재 시세와 최신 기사만 확인할 수 있습니다. 과거 특정 시점의 시세나 기사는 일·주·월·년 차트나 기사 목록에서 직접 확인해 주세요.", reply_status="no_data")

    if category == "comparison":
        if len(named) < 2:
            return clarify("비교할 기업 두 곳을 알려 주세요.")
        if not has(text, METRIC_WORDS):
            return clarify("어떤 지표를 비교할까요? 현재가, 매출액, 영업이익, 당기순이익 중에서 알려 주세요.")
    elif category != "concept":
        named = named or [selected]

    return Interpretation(category, named, tools_for(category, text), year)


def interpret(message: str, selected: str, companies: list[dict], previous) -> Interpretation:
    text = compact(message)
    named = find_companies(message, companies)
    year_match = YEAR_PATTERN.search(message)
    year = int(year_match.group(1)) if year_match else relative_year(text)
    category = category_of(text, named)

    candidates = partial_company(message, companies)
    if candidates is not None and not named:
        hint = f" 지원 기업: {', '.join(candidates)}." if candidates else ""
        return clarify(f"어느 기업을 말씀하시나요?{hint} 정확한 기업명과 궁금한 내용을 알려 주세요.")

    unknown = unknown_company(message, named)
    if unknown:
        plan = unsupported_company(unknown)
        plan.ask_llm = True
        plan.unknown = unknown
        plan.wanted = category
        plan.year = year
        return plan

    # 앞 대화에 이어지는 짧은 질문: 앞선 질문 종류와 기업을 그대로 씁니다
    if previous.category:
        follow_up = (FOLLOW_UP_START.match(text) or has(text, REFERENCE_WORDS) or ALSO_ASKED.search(text)
                     or (category is None and has(text, FOLLOW_UP_WORDS)))
        if follow_up or (named and category is None):
            category = category or previous.category
            named = named or ([previous.company] if previous.company else [])
            year = year or previous.year

    if category is None:
        if named:
            # 지원 기업은 알지만 무엇을 묻는지 모를 때만 LLM 해석을 씁니다. 금융과 무관한 질문은 보내지 않습니다
            plan = clarify(f"{named[0]}의 현재가, 실적, 뉴스 중 어떤 정보를 알려 드릴까요?")
            plan.ask_llm = True
            return plan
        return Interpretation("unrelated", reply="K-FinCheck 는 주식·기업 정보와 금융 용어에 관한 질문을 도와드립니다.")

    return finish(category, named, text, year, selected)


def apply_llm_reading(reading, message: str, selected: str, companies: list[dict], plan: Interpretation) -> Interpretation:
    """LLM 해석 결과를 서버 규칙으로 검사합니다. 기업과 Tool 은 지원 목록과 규칙 안에서만 정합니다."""
    names = {c["companyName"] for c in companies}
    if reading.category == "unrelated":
        return Interpretation("unrelated", reply="K-FinCheck 는 주식·기업 정보와 금융 용어에 관한 질문을 도와드립니다.")
    if reading.category == "clarify" or reading.category not in LENGTHS:
        return plan

    named = find_companies(message, companies)
    if reading.company == "selected" and not plan.unknown:
        company = named[0] if named else selected
    elif reading.company in names and (not named or reading.company in named):
        company = reading.company
    else:
        return plan
    # 'Tesla → 삼성전자' 같은 잘못된 해석을 막습니다. 비슷한 표기(오타)일 때만 지원 기업으로 인정합니다
    if plan.unknown and SequenceMatcher(None, compact(plan.unknown), compact(company)).ratio() < 0.6:
        return plan

    text = compact(message)
    year_match = YEAR_PATTERN.search(message)
    return finish(reading.category, [company], text, int(year_match.group(1)) if year_match else None, selected)
