"""질문에서 기업을 찾습니다. 지원 기업 목록은 Spring(company 테이블)에서 받아 종목이 늘어도 코드를 고치지 않습니다."""

import re
import time

from .spring_client import SpringClient, SpringError

ALIASES = {"삼전": "삼성전자", "하이닉스": "SK하이닉스", "하닉": "SK하이닉스", "현차": "현대차", "현대자동차": "현대차", "네이버": "NAVER", "기아차": "기아", "엘지화학": "LG화학", "삼성에스디아이": "삼성SDI"}
GROUP_NAMES = ("삼성", "현대", "sk", "lg")  # 이 이름만으로는 기업을 정할 수 없습니다
TARGET_WORDS = r"현재가|주가|시세|실적|매출|영업이익|순이익|뉴스|기사|전망"
QUALIFIERS = r"최근|최신|오늘|지금|현재|요즘|올해|작년|관련"  # 기업명과 조회 단어 사이에 오는 말
OPINION_WORDS = r"왜|사도|살까|팔까"
NOT_COMPANY = {
    "최근", "오늘", "현재", "최신", "요즘", "올해", "작년", "이", "그", "회사", "기업", "종목", "이회사", "그회사",
    "이거", "저거", "그것", "이것", "저것", "얘", "걔", "이건", "주식이", "주가가", "주가는",
    "해당", "관련", "연간", "연결", "전체", "국내", "미국", "시장", "내", "제", "주식", "지금", "오늘의", "이번", "최근의",
    # 앞 대화를 잇는 말
    "그럼", "그러면", "그건", "그게", "그거", "이건", "이게", "그래서", "그리고", "다시", "좀더", "거기", "여기", "저기",
    # 시점
    "어제", "그제", "내일", "내년", "재작년", "전년", "지난해", "지난주", "지난달", "이번주", "이번달", "다음주", "다음달",
    # 실적 구분과 금융 용어의 앞부분 (당기순이익, 주당순이익 등)
    "분기", "반기", "별도", "예상", "당기", "전기", "주당", "세전", "세후", "영업", "누적", "주요", "다른",
}


# '카카오뱅크', '현대차증권' 처럼 지원 기업명 뒤에 글자가 더 붙은 다른 회사를 걸러 냅니다
LONGER_NAME = re.compile(
    rf"(?!의|은|는|이|가|을|를|도|와|과|랑|하고|에|로|만|보다|요|및|vs|{TARGET_WORDS}"
    r"|현재|최근|오늘|지금|요즘|어때|어떤|왜|관련|실적|회사|기업|종목|비교|알려|정보|얼마|사도|살까|팔까|뭐|거래량|등락|공시|소식)[가-힣a-z0-9]"
)


def compact(text: str) -> str:
    return re.sub(r"\s+", "", text).casefold()


class CompanyDirectory:
    """지원 기업 목록을 1분 동안 재사용합니다."""

    def __init__(self, spring: SpringClient, ttl_seconds: float = 60):
        self.spring = spring
        self.ttl_seconds = ttl_seconds
        self.items: list[dict] = []
        self.loaded_at = 0.0

    async def load(self) -> list[dict]:
        now = time.monotonic()
        if self.items and now - self.loaded_at < self.ttl_seconds:
            return self.items
        try:
            self.items = await self.spring.all_companies()
            self.loaded_at = now
        except SpringError:
            if not self.items:
                raise
        return self.items


def find_companies(message: str, companies: list[dict]) -> list[str]:
    """질문에 나온 지원 기업명을 질문 순서대로 돌려줍니다."""
    text = compact(message)
    found: list[tuple[int, str]] = []
    aliases = {compact(alias): name for alias, name in ALIASES.items()}
    for company in companies:
        name, code = company["companyName"], company["stockCode"]
        for token in [compact(name), code, *[a for a, n in aliases.items() if n == name]]:
            at = text.find(token)
            if at >= 0 and not LONGER_NAME.match(text[at + len(token):]):
                found.append((at, name))
                break
    return [name for _, name in sorted(found)]


def partial_company(message: str, companies: list[dict]) -> list[str] | None:
    """'삼성 어때?' 처럼 그룹 이름만 말하면 지원 기업 중 후보를 돌려줍니다."""
    text = compact(message)
    for word in GROUP_NAMES:
        if re.match(rf"^{word}(의|은|는|이|가)?({TARGET_WORDS}|어때|어떄|어떤가|어떰|정보|소식|알려|좀|[?!.]*$)", text):
            return [c["companyName"] for c in companies if compact(c["companyName"]).startswith(word)]
    return None


def unknown_company(message: str, known: list[str]) -> str | None:
    """'테슬라 주가' 처럼 기업처럼 보이지만 지원 목록에 없는 이름을 찾습니다."""
    if known:
        return None
    match = re.search(rf"([가-힣A-Za-z0-9]{{2,20}}?)(?:의|은|는|이|가)?\s*(?:{QUALIFIERS})?\s*(?:{TARGET_WORDS}|{OPINION_WORDS})", message)
    if not match:
        return None
    name = match.group(1)
    if name in NOT_COMPANY or name[0].isdigit() or re.search(TARGET_WORDS, name):
        return None
    return name
