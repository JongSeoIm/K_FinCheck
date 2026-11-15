"""4단계. 종목 사전(제목 키워드 매칭 전용).

- 종목명·별칭: 공식 종목명, 위키데이터(CC0) 한글·영문 이름, 직접 보충한 줄임말
- 산업 키워드: 위키데이터 업종·제품 정보와 공개된 사업 분야에서 단어만 정리
- 제외 표현: 종목명과 글자가 같지만 다른 뜻인 표현(예: 기아 타이거즈)
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class StockEntry:
    code: str
    name: str
    aliases: tuple[str, ...]
    industry_keywords: tuple[str, ...]
    excludes: tuple[str, ...] = ()


STOCKS: dict[str, StockEntry] = {s.code: s for s in (
    StockEntry("005930", "삼성전자", ("삼성전자", "삼전", "Samsung Electronics"),
               ("반도체", "메모리", "HBM", "D램", "낸드", "파운드리", "스마트폰", "가전"), ("삼성전자서비스",)),
    StockEntry("000660", "SK하이닉스", ("SK하이닉스", "하이닉스", "SK hynix"),
               ("반도체", "메모리", "HBM", "D램", "낸드")),
    StockEntry("035420", "NAVER", ("네이버", "NAVER"),
               ("포털", "검색", "웹툰", "이커머스", "플랫폼", "생성형 AI")),
    StockEntry("035720", "카카오", ("카카오",),
               ("플랫폼", "메신저", "핀테크", "콘텐츠", "생성형 AI")),
    StockEntry("005380", "현대차", ("현대차", "현대자동차", "Hyundai Motor"),
               ("자동차", "완성차", "전기차", "수소차", "자동차 관세"), ("현대차증권",)),
    StockEntry("000270", "기아", ("기아", "Kia"),
               ("자동차", "완성차", "전기차", "자동차 관세"),
               ("기아 타이거즈", "KIA 타이거즈", "기아타이거즈", "기아챔피언스필드")),
    StockEntry("051910", "LG화학", ("LG화학", "엘지화학"),
               ("석유화학", "배터리", "양극재", "이차전지", "첨단소재")),
    StockEntry("006400", "삼성SDI", ("삼성SDI",),
               ("배터리", "이차전지", "전고체", "ESS", "전기차")),
)}
