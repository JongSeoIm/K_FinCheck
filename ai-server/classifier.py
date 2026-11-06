"""질문 분류 규칙 (PDF "Tool 선택 방식")

FastAPI 가 질문 유형을 판별하고 필요한 Tool 을 고른다. OpenAI 는 Tool 을 고르지 않는다.
규칙으로 판단하기 애매한 질문은 confident=False 로 표시하고, interpreter 가 OpenAI 로 질문을 해석해 다시 판단한다.

  "부채비율이 뭐야?"            → Tool 없이 개념 답변
  "삼성전자 현재가 알려줘"       → get_stock_quote
  "삼성전자 최근 실적 알려줘"    → get_financial_result
  "삼성전자 관련 뉴스 알려줘"    → get_company_news
  "삼성전자 왜 올랐어?"          → get_stock_quote + get_company_news (+ 실적 단어가 있으면 get_financial_result)
"""

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

# 한국 시간 (서머타임이 없어 고정 +09:00. Windows 에 시간대 DB가 없어도 동작)
KST = timezone(timedelta(hours=9), "KST")

# 질문 유형 (프롬프트의 답변 분량 기준과 연결)
TERM = "term"                # 금융 용어 설명
QUOTE = "quote"              # 현재가·등락
FINANCIAL = "financial"      # 기업 실적
NEWS = "news"                # 관련 기사
MOVEMENT = "movement"        # 주가 변동 원인
OUTLOOK = "outlook"          # 전망·매수 여부
COMPANY = "company"          # 기업 정보
COMPARE = "compare"          # 기업 비교

# 결과 종류
DATA = "data"                        # Tool 조회 후 LLM 답변
CONCEPT = "concept"                  # Tool 없이 LLM 답변
OUT_OF_SCOPE = "out_of_scope"        # LLM 호출 없이 고정 안내
NEED_MORE_INFO = "need_more_info"    # LLM 호출 없이 조건 다시 질문
NO_DATA = "no_data"                  # 조회할 Tool 이 없는 자료. LLM 호출 없이 확인 가능한 범위 안내

QUOTE_TOOL = "get_stock_quote"
FINANCIAL_TOOL = "get_financial_result"
NEWS_TOOL = "get_company_news"
INFO_TOOL = "get_company_info"
TOOL_INTENT = {QUOTE_TOOL: QUOTE, FINANCIAL_TOOL: FINANCIAL, NEWS_TOOL: NEWS, INFO_TOOL: COMPANY}

# 종목 줄임말 (종목명은 항상 포함)
ALIASES = {
    "005930": ["삼성전자", "삼성", "삼전", "samsung"],
    "000660": ["sk하이닉스", "하이닉스", "에스케이하이닉스", "하닉", "skhynix", "hynix"],
}

QUOTE_WORDS = ["현재가", "주가", "시세", "가격", "얼마", "등락", "거래량", "시가총액", "몇 원", "몇원", "52주", "최고가", "최저가"]
FINANCIAL_WORDS = ["실적", "매출", "영업이익", "순이익", "이익", "재무", "부채", "자산", "자본", "분기", "연간", "사업보고서",
                   "손익", "흑자", "적자", "어닝", "eps"]
# 조사가 붙은 단어를 찾을 때 쓰는 조사 목록
_PARTICLES = "의|이|가|은|는|을|를|도|만|와|과|랑|로|율|란|에"
# 다른 단어의 일부로 흔한 시세 단어는 단어 전체일 때만 인정한다 ("최고가 호텔"의 '고가'는 시세가 아니다)
SHORT_QUOTE_PATTERN = re.compile(rf"(?<![가-힣])(?:종가|시가|고가|저가)(?:{_PARTICLES})?(?![가-힣])")
NEWS_WORDS = ["뉴스", "기사", "소식", "이슈", "보도", "헤드라인"]
# "회사야"·"회사면"의 '사야'·'사면'은 매수 질문이 아니다
ADVICE_PATTERN = re.compile(
    r"((?<!회)사도|살까|(?<!회)사야|(?<!회)사면|팔까|팔아|팔면|매수해|매도해|매수할|매도할|매수하|매도하|들어가도|전망|오를까|내릴까|떨어질까|목표가"
    r"|투자해|투자할까|투자하면|투자하는 ?게|투자 ?어때|담아도|물타기"
    # 같은 뜻의 다른 표현 ("구매해도 될까?", "사볼까?", "살만해?")
    r"|구매해|구매할|구매하|구입해|구입할|구입하|매입해|매입할|매입하|사볼까|사둘까|(?<!회)사려고|사고 ?싶|살 ?만|(?<![가-힣])사는 ?게|손절|익절|추매)")
MOVEMENT_PATTERN = re.compile(
    r"(왜|이유|원인|무슨 일|뭔 일).{0,15}(올|오르|상승|내려|내리|떨어|하락|급등|급락|빠지|빠졌|폭락|폭등|강세|약세)"
    r"|(올|오르|상승|내려|내리|떨어|하락|급등|급락|빠지|빠졌|폭락|폭등|강세|약세).{0,10}(왜|이유|원인)")
INFO_PATTERN = re.compile(r"(어떤 회사|무슨 회사|뭐 하는 회사|뭐하는 회사|어디 상장|종목 ?코드|시장 구분|어느 시장|회사 소개)")
CONCEPT_PATTERN = re.compile(
    r"(뭐야|뭔가요|뭐예요|뭐에요|뭔데|무엇|뜻|의미|정의|이란|란\?|란 |어떻게 계산|어떻게 읽|어떻게 봐|어떻게 보|설명해|개념|차이가 뭐|차이는|달라|다른가|다른 점|무슨 뜻"
    r"|계산법|계산 ?방법|계산식|구하는 ?법|구하는 ?방법|공식|높으면|낮으면|높을수록|낮을수록|차이(?:를|점)?\s*(?:알려|설명))")
COMPARE_PATTERN = re.compile(r"(비교|vs|대비해서|누가 더|어디가 더|어느 쪽|차이)")
FOLLOW_UP_PATTERN = re.compile(
    r"^(?:(?:그럼|그러면|그래서|그리고)(?=\s|$)|그건|그거|그게|그 ?회사|재작년|작년|올해|전년|이전|지난해|더 자세히|자세히"
    r"|(?:더|왜|어때|어떤데|진짜|정말)[\s?!.]*$)")
# 짧은 질문이 기간만 바꿔 묻는 후속 질문인지 ("2024년은?", "1분기는?")
FOLLOW_UP_PERIOD_PATTERN = re.compile(r"(20\d{2}|재작년|작년|올해|전년|지난해|분기|반기|연간)")
# 주가 변동 질문의 기준 기간 (PDF "질문 조건이 부족한 경우")
PERIOD_PATTERN = re.compile(r"(오늘|어제|그제|방금|아까|지금|최근|요즘|이번 ?주|지난 ?주|이번 ?달|지난 ?달|올해|작년|\d+\s*(일|주|개월|월|년))")

# 금융과 관련 있는지 판단하는 단어 (하나도 없고 기업명도 없으면 서비스 범위 밖)
FINANCE_TERMS = [
    "주식", "주가", "증권", "증시", "투자", "매수", "매도", "종목", "상장", "코스피", "코스닥", "kospi", "kosdaq", "지수",
    "per", "pbr", "roe", "roa", "eps", "bps", "ebitda", "부채비율", "유동비율", "영업이익률", "순이익률", "배당",
    "시가총액", "공매도", "액면분할", "유상증자", "무상증자", "etf", "펀드", "채권", "금리", "환율", "인플레이션",
    "재무제표", "손익계산서", "재무상태표", "현금흐름", "감가상각", "매출원가", "영업이익", "순이익", "자본", "부채",
    "자산", "실적", "매출", "공시", "호가", "상한가", "하한가", "거래량", "종가", "시가", "고가", "저가", "분산투자",
    "포트폴리오", "레버리지", "인버스", "증자", "감자", "자사주", "목표가", "투자의견",
    "애널리스트", "경기침체", "금융", "은행", "대출", "이자", "예금", "적금", "연금", "isa", "주주", "경영권",
    "인수합병", "m&a", "ipo", "반도체", "hbm", "d램", "디램", "낸드", "파운드리", "시세", "현재가",
    "등락", "급등", "급락", "실적발표", "분기", "사업보고서", "어닝",
]
# 기업명처럼 보여도 금융 단어면 지원하지 않는 기업으로 보지 않는다 ("매출과 영업이익")
_PARTICLE_SUFFIX = re.compile(r"(과|와|랑|이랑|하고|및|도|만)$")

# "카카오 주가" 처럼 지원하지 않는 기업을 찾기 위한 패턴 (앞 단어가 기업명처럼 보이면)
UNKNOWN_COMPANY_PATTERN = re.compile(r"([가-힣A-Za-z0-9&]{2,15}?)(?:의|이|가|은|는)?\s*(?:현재가|주가|시세|실적|매출|영업이익|뉴스|기사|주식|재무)")
GENERIC_WORDS = {
    "오늘", "지금", "현재", "최근", "요즘", "이번", "작년", "올해", "내년", "전년", "지난", "어제", "회사", "이회사", "이 회사",
    "종목", "이종목", "우리", "전체", "분기", "연간", "최신", "관련", "주요", "국내", "미국", "해외", "반도체", "증시", "시장",
    "코스피", "코스닥", "업계", "경제", "금융", "금리", "환율", "업종", "섹터", "기업", "대형주", "중소형주", "테마", "그",
    "이", "저", "여기", "거기", "이번주", "이번달", "올해의", "연말", "상반기", "하반기", "1분기", "2분기", "3분기", "4분기",
    "실시간", "평균", "예상", "목표", "적정", "하루", "주간", "월간", "개인", "외국인", "기관", "메모리", "hbm",
    "내일", "어제", "방금", "아까", "그거", "이거", "저거", "업황", "경기", "내년", "다음주", "다음달", "장중", "장마감",
}

# 다른 단어의 일부로 흔한 금융 단어는 단어 전체일 때만 인정한다 ("이자카야", "연금술사", "감자튀김")
_AMBIGUOUS_TERMS = {"이자", "연금", "은행", "감자", "부채", "시가", "고가", "저가", "종가", "지수", "상장", "분기"}


def _finance_term_pattern(term: str) -> str:
    escaped = re.escape(term)
    if term.isascii():  # "per" 가 "super" 에 걸리지 않도록
        return rf"(?<![a-z]){escaped}(?![a-z])"
    if term in _AMBIGUOUS_TERMS:
        return rf"(?<![가-힣]){escaped}(?:{_PARTICLES})?(?![가-힣])"
    return escaped


FINANCE_PATTERN = re.compile("|".join(_finance_term_pattern(t) for t in FINANCE_TERMS))

# 우리가 지원하지 않는 주요 상장사. 조회 Tool 을 부르지 않고 지원 종목 안내로 답한다
# ("삼성바이오"가 줄임말 '삼성' 때문에 삼성전자로 잡히지 않도록 삼성 계열사도 넣는다)
_OTHER_COMPANIES = [
    "카카오", "네이버", "현대차", "현대자동차", "현대모비스", "기아", "LG전자", "LG화학", "LG에너지솔루션", "LG엔솔",
    "삼성바이오로직스", "삼성바이오", "삼성SDI", "삼성물산", "삼성생명", "삼성증권", "삼성화재", "삼성전기", "삼성중공업",
    "SK텔레콤", "SK이노베이션", "SK스퀘어", "KT", "포스코", "POSCO", "셀트리온", "한화", "두산", "크래프톤", "쿠팡", "토스",
    "에코프로", "KB금융", "신한지주", "하나금융", "대한항공", "테슬라", "애플", "엔비디아", "구글", "아마존", "마이크로소프트", "메타",
]
OTHER_COMPANY_PATTERN = re.compile(
    r"(?<![가-힣a-zA-Z0-9])(" + "|".join(sorted(map(re.escape, _OTHER_COMPANIES), key=len, reverse=True)) + r")"
    rf"(?:{_PARTICLES}|하고|주식|주가|주)?(?![가-힣a-zA-Z0-9])", re.I)
COMPANY_ASK_PATTERN = re.compile(r"(어때|어떤가|어떤지|어떨까|괜찮|좋아|좋을까|어떻게 생각)")
# 줄임말 '삼성' 뒤에 조사·질문 단어가 아닌 말이 붙으면 삼성전자가 아닌 다른 회사다 ("삼성카드", "삼성자산운용")
_SAMSUNG_WORD = re.compile(r"(?<![가-힣a-zA-Z0-9])삼성([가-힣a-zA-Z0-9]+)")
_SAMSUNG_PARTICLE = re.compile(rf"(?:{_PARTICLES}|이랑|하고|에서|주)")
_SAMSUNG_QUESTION = re.compile(r"(?:전자|주가|주식|현재가|시세|실적|매출|영업이익|뉴스|기사|왜|어때)")
_WORD_PARTICLE = re.compile(rf"(?:이랑|하고|{_PARTICLES})$")
# 지수·환율·금리처럼 조회 Tool 이 없는 값을 묻는 질문 ("코스피 지수 얼마야?", "금리 얼마야?")
MARKET_VALUE_PATTERN = re.compile(
    r"(코스피|코스닥|나스닥|다우|s&p|지수|환율|달러|엔화|유로|기준금리|금리|유가|금값|비트코인)[^?!.]{0,12}?(?:얼마|몇|시세|현재가|가격|등락)", re.I)

# 금융 단어가 없을 때 이 주제 단어가 있으면 서비스 범위 밖이다
UNRELATED_WORDS = [
    "날씨", "점심", "저녁", "아침밥", "메뉴", "맛집", "요리", "레시피", "영화", "드라마", "예능", "게임", "노래", "여행", "호텔", "항공권",
    "연애", "숙제", "자격증", "시험", "수능", "코딩", "파이썬", "택시",
]
# 금융 단어 없이 "OO 가격", "OO 사도 돼?"처럼 다른 대상을 묻는지 찾는다
OTHER_SUBJECT_PATTERN = re.compile(
    r"([가-힣A-Za-z0-9&]{2,15}?)(?:의|이|가|은|는|을|를|도|만)?\s*"
    r"(?:가격|얼마|몇 ?원|기사|소식|이슈|보도|왜|사도|살까|사야|사면|팔까|팔아|팔면|전망|오를까|내릴까|떨어질까|투자해|투자할까|담아도|들어가도"
    r"|구매|구입|매입|사볼까|사둘까|사려고|사고 ?싶|살 ?만|사는 ?게)")


@dataclass
class Company:
    symbol: str
    name: str
    market: str = ""


@dataclass
class ToolCall:
    name: str
    symbol: str
    year: str | None = None
    report: str | None = None
    company: str = ""          # 기업명 (뉴스 Tool 이 제목에 이 기업이 나온 기사를 먼저 고를 때 사용)


@dataclass
class Plan:
    kind: str
    question_type: str = TERM
    targets: list[Company] = field(default_factory=list)
    calls: list[ToolCall] = field(default_factory=list)
    message: str = ""          # 고정 안내 문구 (out_of_scope · need_more_info)
    confident: bool = True     # False: 규칙에 걸린 단어가 없어 기본값으로 판단함 (interpreter 대상)


OUT_OF_SCOPE_MESSAGE = "저는 종목 시세, 실적, 관련 뉴스와 금융 용어 같은 주식·금융 질문만 도와드릴 수 있어요."


def _norm(text: str) -> str:
    return text.lower().strip()


def _has_any(text: str, words: list[str]) -> bool:
    return any(w in text for w in words)


def find_companies(text: str, companies: list[Company]) -> list[Company]:
    """질문에 나온 지원 종목 (나온 순서대로, 중복 없이)"""
    lowered = _norm(text).replace(" ", "")
    found: list[tuple[int, Company]] = []
    for company in companies:
        names = [company.name.lower().replace(" ", ""), company.symbol] + ALIASES.get(company.symbol, [])
        positions = [lowered.find(n.replace(" ", "")) for n in names if n and n.replace(" ", "") in lowered]
        if positions:
            found.append((min(positions), company))
    found.sort(key=lambda item: item[0])
    return [company for _, company in found]


def _unsupported_plan(word: str, companies: list[Company], intents: list[str]) -> Plan:
    names = ", ".join(c.name for c in companies)
    return Plan(kind=NEED_MORE_INFO, question_type=intents[0] if intents else COMPANY,
                message=f"지금은 {names} 종목만 조회할 수 있어요. '{word}'은(는) 아직 지원하지 않아요. 지원 종목으로 다시 질문해 주세요.")


def _is_other_samsung(tail: str) -> bool:
    return not (_SAMSUNG_PARTICLE.fullmatch(tail) or _SAMSUNG_QUESTION.match(tail))


def _other_samsung(text: str) -> str | None:
    """'삼성'으로 시작하는 다른 회사 이름 ("삼성카드는" → "삼성카드")"""
    for match in _SAMSUNG_WORD.finditer(text):
        if _is_other_samsung(match.group(1)):
            return _WORD_PARTICLE.sub("", match.group(0))
    return None


def _without_other_companies(text: str) -> str:
    """지원 종목을 찾기 전에 다른 회사 이름을 지운다 ("삼성카드"가 줄임말 '삼성'에 걸리지 않도록)"""
    text = OTHER_COMPANY_PATTERN.sub(" ", text)
    return _SAMSUNG_WORD.sub(lambda m: " " if _is_other_samsung(m.group(1)) else m.group(0), text)


def _other_subject(text: str) -> str | None:
    """금융 단어 없는 질문에서 가격·전망을 묻는 다른 대상 ("아이폰 가격", "가방 사도 돼?")"""
    for match in OTHER_SUBJECT_PATTERN.finditer(text):
        word = match.group(1).strip()
        if word.lower() not in GENERIC_WORDS:
            return word
    return None


def _unknown_company(text: str, companies: list[Company]) -> str | None:
    """지원하지 않는 기업명처럼 보이는 단어 (없으면 None)"""
    known = set()
    for company in companies:
        known.add(company.name.lower().replace(" ", ""))
        known.update(a.replace(" ", "") for a in ALIASES.get(company.symbol, []))
    for match in UNKNOWN_COMPANY_PATTERN.finditer(text):
        word = _PARTICLE_SUFFIX.sub("", match.group(1).strip())
        compact = word.lower().replace(" ", "")
        if len(compact) < 2 or compact in GENERIC_WORDS or word in GENERIC_WORDS:
            continue
        if _has_any(compact, FINANCE_TERMS + QUOTE_WORDS + FINANCIAL_WORDS + NEWS_WORDS):
            continue
        if any(k in compact or compact in k for k in known):
            continue
        if re.fullmatch(r"\d+", compact) or re.fullmatch(r"(19|20)\d{2}년?", compact):
            continue
        return word
    return None


def _financial_period(text: str) -> tuple[str | None, str | None]:
    """질문에 나온 사업연도·보고서 (없으면 최근 공시)"""
    now = datetime.now(KST)
    year = None
    match = re.search(r"(20\d{2})\s*년?", text)
    if match:
        year = match.group(1)
    elif "재작년" in text:
        year = str(now.year - 2)
    # "전년 대비"는 비교 기준이지 조회할 사업연도가 아니다 (최근 공시로 답한다)
    elif "작년" in text or "지난해" in text or re.search(r"전년(?!\s*(?:대비|동기|같은))", text):
        year = str(now.year - 1)
    elif "올해" in text:
        year = str(now.year)

    report = None
    if re.search(r"1\s*분기|일사분기|1q", text, re.I):
        report = "11013"
    elif re.search(r"반기|2\s*분기|상반기|2q", text, re.I):
        report = "11012"
    elif re.search(r"3\s*분기|3q", text, re.I):
        report = "11014"
    elif re.search(r"사업보고서|연간|1년 실적|연 실적|4\s*분기|결산", text):
        report = "11011"
    return year, report


def _intents(text: str, mentioned: list[Company]) -> list[str]:
    lowered = _norm(text)
    intents: list[str] = []
    if MOVEMENT_PATTERN.search(lowered):
        intents.append(MOVEMENT)
    if ADVICE_PATTERN.search(lowered):
        intents.append(OUTLOOK)
    if _has_any(lowered, QUOTE_WORDS) or SHORT_QUOTE_PATTERN.search(lowered):
        intents.append(QUOTE)
    # '성적'은 기업을 말했을 때만 실적으로 본다 ("시험 성적"과 구분)
    if _has_any(lowered, FINANCIAL_WORDS) or (mentioned and "성적" in lowered):
        intents.append(FINANCIAL)
    if _has_any(lowered, NEWS_WORDS):
        intents.append(NEWS)
    if INFO_PATTERN.search(lowered):
        intents.append(COMPANY)
    return intents


def _is_finance(text: str) -> bool:
    return bool(FINANCE_PATTERN.search(_norm(text)))


def _is_follow_up(text: str, mentioned: list[Company]) -> bool:
    if FOLLOW_UP_PATTERN.search(text):
        return True
    return len(text) <= 15 and (bool(mentioned) or bool(FOLLOW_UP_PERIOD_PATTERN.search(text)))


def classify(question: str, current: Company, companies: list[Company], history: list[dict] | None = None) -> Plan:
    text = question.strip()
    lowered = _norm(text)
    other_match = OTHER_COMPANY_PATTERN.search(text)
    other_name = other_match.group(1) if other_match else _other_samsung(text)
    mentioned = find_companies(_without_other_companies(text), companies)
    intents = _intents(text, mentioned)
    finance_word = _is_finance(text)
    # 대화창은 종목 화면에서 열리므로 "왜 올랐어?", "지금 얼마야?"처럼 유형 단어만 있어도 금융 질문으로 본다
    finance = finance_word or bool(mentioned) or bool(intents)

    # 지원하지 않는 주요 상장사는 Tool 을 부르지 않는다 ("현대차 얼마야?", "테슬라 사도 돼?")
    if other_name and (finance or COMPANY_ASK_PATTERN.search(lowered)):
        return _unsupported_plan(other_name, companies, intents)

    # 조회 Tool 이 없는 값은 대화 중인 종목의 시세로 대신 답하지 않는다
    market_value = MARKET_VALUE_PATTERN.search(text) if not mentioned and not CONCEPT_PATTERN.search(lowered) else None
    if market_value:
        names = ", ".join(c.name for c in companies)
        return Plan(kind=NO_DATA, message=f"{market_value.group(1)} 값은 지금 조회할 수 없어요. {names}의 현재가, 실적, 관련 뉴스는 확인할 수 있어요.")

    # 금융과 무관한 주제는 대화 중이어도 LLM 에 보내지 않고 안내 문구 ("오늘 날씨 어때?")
    if not mentioned and not finance_word and _has_any(lowered, UNRELATED_WORDS):
        return Plan(kind=OUT_OF_SCOPE, message=OUT_OF_SCOPE_MESSAGE)

    previous = next((h["content"] for h in reversed(history or []) if h.get("role") == "user"), None)

    # 직전에 비교 지표·기준 기간을 되물었다면 이번 답을 합쳐서 본다 ("매출로", "오늘")
    if previous and len(text) <= 15 and not CONCEPT_PATTERN.search(lowered):
        asked = classify(previous, current, companies)
        if asked.kind == NEED_MORE_INFO and asked.question_type in (COMPARE, MOVEMENT):
            merged = classify(previous + " " + text, current, companies)
            if merged.kind == DATA:
                return merged

    # 앞 대화를 잇는 후속 질문 ("그럼 작년은?", "하이닉스는?")은 직전 사용자 질문의 유형을 이어받는다
    if not intents and previous and _is_follow_up(text, mentioned):
        if classify(previous, current, companies).kind in (DATA, CONCEPT):
            # 이번 질문의 기간(작년 등)은 반영하고, 기업을 새로 말했으면 그 기업으로 바꾼다
            plan = classify(previous + " " + text, current, companies)
            if mentioned and plan.kind == DATA and plan.question_type != COMPARE:
                tools = list(dict.fromkeys(call.name for call in plan.calls))
                plan.targets = mentioned
                plan.calls = _calls([TOOL_INTENT[name] for name in tools], mentioned, previous + " " + text)
            return plan

    # 금융 단어도, 지원 기업명도 없으면 일단 안내 문구 ("이 회사 돈 잘 벌어?"는 interpreter 가 다시 본다)
    if not finance:
        return Plan(kind=OUT_OF_SCOPE, message=OUT_OF_SCOPE_MESSAGE, confident=False)

    # 금융 단어 없이 다른 대상의 가격·전망을 묻는 질문 ("아이폰 가격 얼마야?", "가방 사도 돼?")
    if not mentioned and not finance_word and _other_subject(text):
        return Plan(kind=OUT_OF_SCOPE, message=OUT_OF_SCOPE_MESSAGE, confident=False)

    # 지원하지 않는 기업 (예: "쏘카 주가")
    unknown = _unknown_company(text, companies) if not mentioned else None
    if unknown and intents:
        return _unsupported_plan(unknown, companies, intents)

    # 개념 질문: 기업을 콕 집지 않고 뜻·계산 방법을 물으면 Tool 없이 설명
    if CONCEPT_PATTERN.search(lowered) and not mentioned and not ({QUOTE, NEWS, MOVEMENT, COMPANY} & set(intents)):
        return Plan(kind=CONCEPT, question_type=TERM)

    # 기업 비교: 두 기업 이상이 나와야 한다
    if COMPARE_PATTERN.search(lowered) and (len(mentioned) >= 2 or "비교" in lowered):
        if len(mentioned) < 2:
            others = [c.name for c in companies if not mentioned or c.symbol != mentioned[0].symbol]
            return Plan(kind=NEED_MORE_INFO, question_type=COMPARE,
                        message=f"어떤 기업과 비교할까요? 비교할 기업(예: {', '.join(others)})과 보고 싶은 지표(현재가, 매출, 영업이익 등)를 함께 알려 주세요.")
        targets = mentioned
        compare_intents = [i for i in intents if i in (QUOTE, FINANCIAL, NEWS)]
        if not compare_intents:
            names = "와 ".join(c.name for c in targets)
            return Plan(kind=NEED_MORE_INFO, question_type=COMPARE,
                        message=f"{names}를 어떤 지표로 비교할까요? 현재가, 매출, 영업이익처럼 보고 싶은 지표를 알려 주세요.")
        return Plan(kind=DATA, question_type=COMPARE, targets=targets,
                    calls=_calls(compare_intents, targets, text))

    targets = mentioned or [current]
    confident = True

    if not intents:
        if mentioned:
            # "삼성전자 어때?" → 지금 상황을 볼 수 있게 시세 + 뉴스
            intents = [QUOTE, NEWS]
            confident = False
        else:
            # 기업 없는 일반 금융 질문 ("주식 처음인데 뭐부터 봐야 해?")
            return Plan(kind=CONCEPT, question_type=TERM, confident=False)

    if MOVEMENT in intents:
        # 기업명도 기간도 말하지 않았으면 다시 묻는다. 기업을 말한 질문은 PDF 예시("삼성전자 왜 올랐어?")대로 최근 등락을 조회한다
        if not mentioned and not PERIOD_PATTERN.search(lowered):
            return Plan(kind=NEED_MORE_INFO, question_type=MOVEMENT,
                        message=f"어느 기업의 어느 기간 등락이 궁금한가요? 예: {current.name} 오늘")
        question_type = MOVEMENT
        needed = [QUOTE, NEWS] + ([FINANCIAL] if FINANCIAL in intents else [])
    elif OUTLOOK in intents:
        question_type = OUTLOOK
        needed = [QUOTE, NEWS] + ([FINANCIAL] if FINANCIAL in intents else [])
    else:
        question_type = intents[0]
        needed = intents

    return Plan(kind=DATA, question_type=question_type, targets=targets, calls=_calls(needed, targets, text), confident=confident)


def _calls(intents: list[str], targets: list[Company], text: str) -> list[ToolCall]:
    year, report = _financial_period(text)
    calls: list[ToolCall] = []
    for company in targets:
        for intent in intents:
            if intent == QUOTE:
                calls.append(ToolCall(QUOTE_TOOL, company.symbol))
            elif intent == FINANCIAL:
                calls.append(ToolCall(FINANCIAL_TOOL, company.symbol, year, report))
            elif intent == NEWS:
                calls.append(ToolCall(NEWS_TOOL, company.symbol, company=company.name))
            elif intent == COMPANY:
                calls.append(ToolCall(INFO_TOOL, company.symbol))
    # 같은 Tool·종목 중복 제거
    unique: dict[tuple, ToolCall] = {}
    for call in calls:
        unique.setdefault((call.name, call.symbol), call)
    return list(unique.values())
