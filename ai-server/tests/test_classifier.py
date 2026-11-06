from datetime import datetime

import pytest

from classifier import (CONCEPT, DATA, FINANCIAL_TOOL, INFO_TOOL, KST, MOVEMENT, NEED_MORE_INFO, NEWS_TOOL, NO_DATA, OUT_OF_SCOPE,
                        OUTLOOK, QUOTE_TOOL, Company, classify, find_companies)

SAMSUNG = Company("005930", "삼성전자", "KOSPI")
HYNIX = Company("000660", "SK하이닉스", "KOSPI")
COMPANIES = [SAMSUNG, HYNIX]
ALL_COMPANIES = COMPANIES + [Company("005380", "현대차", "KOSPI"), Company("035420", "NAVER", "KOSPI"), Company("035720", "카카오", "KOSPI"),
                             Company("000270", "기아", "KOSPI"), Company("051910", "LG화학", "KOSPI"), Company("006400", "삼성SDI", "KOSPI")]


def tools_of(plan):
    return sorted((c.name, c.symbol) for c in plan.calls)


# PDF "질문 → 호출 Tool" 표
@pytest.mark.parametrize("question, expected", [
    ("부채비율이 뭐야?", []),
    ("삼성전자 현재가 알려줘", [(QUOTE_TOOL, "005930")]),
    ("삼성전자 최근 실적 알려줘", [(FINANCIAL_TOOL, "005930")]),
    ("삼성전자 관련 뉴스 알려줘", [(NEWS_TOOL, "005930")]),
    ("삼성전자 왜 올랐어?", sorted([(QUOTE_TOOL, "005930"), (NEWS_TOOL, "005930")])),
])
def test_pdf_examples(question, expected):
    plan = classify(question, SAMSUNG, COMPANIES)
    assert tools_of(plan) == expected


def test_concept_has_no_tools():
    for q in ["부채비율이 뭐야?", "영업이익률은 어떻게 읽어?", "매출과 이익은 어떻게 달라?", "PER 뜻이 뭐예요"]:
        plan = classify(q, SAMSUNG, COMPANIES)
        assert plan.kind == CONCEPT, q
        assert plan.calls == []


def test_movement_with_earnings_adds_financial():
    plan = classify("삼성전자 실적 때문에 왜 떨어졌어?", SAMSUNG, COMPANIES)
    assert plan.question_type == MOVEMENT
    assert tools_of(plan) == sorted([(QUOTE_TOOL, "005930"), (NEWS_TOOL, "005930"), (FINANCIAL_TOOL, "005930")])


def test_without_company_uses_panel_stock():
    plan = classify("지금 주가 얼마야?", HYNIX, COMPANIES)
    assert tools_of(plan) == [(QUOTE_TOOL, "000660")]
    assert classify("오늘 왜 올랐어?", HYNIX, COMPANIES).kind == DATA


def test_movement_without_company_and_period_asks_again():
    plan = classify("왜 올랐어?", HYNIX, COMPANIES)
    assert plan.kind == NEED_MORE_INFO and plan.question_type == MOVEMENT
    history = [{"role": "user", "content": "왜 올랐어?"}, {"role": "assistant", "content": plan.message}]
    answered = classify("오늘", HYNIX, COMPANIES, history)
    assert answered.question_type == MOVEMENT
    assert tools_of(answered) == sorted([(QUOTE_TOOL, "000660"), (NEWS_TOOL, "000660")])


@pytest.mark.parametrize("question", [
    "아이폰 가격 얼마야?", "점심값 얼마 나왔어?", "이자카야 추천해줘", "기사 자격증 어떻게 따?", "가방 사도 돼?",
    "super mario 재밌어?", "연금술사 줄거리 알려줘", "최고가 호텔 추천", "시험 성적 어떻게 올려?", "감자튀김 맛있어?",
])
def test_unrelated_questions_with_finance_like_words(question):
    assert classify(question, SAMSUNG, COMPANIES).kind == OUT_OF_SCOPE


@pytest.mark.parametrize("question", ["오늘 날씨 어때?", "오늘 점심 뭐 먹지?", "고마워", "안녕", "그럼 점심은?"])
def test_unrelated_question_after_quote_is_not_follow_up(question):
    history = [{"role": "user", "content": "삼성전자 현재가 알려줘"}, {"role": "assistant", "content": "..."}]
    assert classify(question, SAMSUNG, COMPANIES, history).kind == OUT_OF_SCOPE


@pytest.mark.parametrize("question, word", [
    ("현대모비스 얼마야?", "현대모비스"), ("테슬라 사도 돼?", "테슬라"), ("LG전자 왜 올랐어?", "LG전자"),
    ("삼성바이오 주가 알려줘", "삼성바이오"), ("카카오뱅크 어때?", "카카오뱅크"), ("쏘카 주가 알려줘", "쏘카"),
])
def test_unsupported_company_calls_no_tool(question, word):
    plan = classify(question, SAMSUNG, COMPANIES)
    assert plan.kind == NEED_MORE_INFO and plan.calls == []
    assert word in plan.message


@pytest.mark.parametrize("question", [
    "테슬라를 구매해도될까?", "테슬라 사도 돼?", "테슬라 구매해도 될까요?", "테슬라 구입할까?", "테슬라 사볼까?", "테슬라 살까?", "테슬라 살만해?",
    "테슬라에 투자해도 될까?", "테슬라 주식 사고 싶어", "테슬라주 어때?", "테슬라 매수해도 돼?", "테슬라 좋아?", "테슬라는 팔아야 해?",
])
def test_same_intent_about_unsupported_company_gets_same_answer(question):
    plan = classify(question, SAMSUNG, COMPANIES)
    assert plan.kind == NEED_MORE_INFO and plan.calls == []
    assert "테슬라" in plan.message


@pytest.mark.parametrize("question", [
    "삼성전자를 구매해도될까?", "삼성전자 사도 돼?", "삼성전자 구입할까?", "삼성전자 사볼까?", "삼성전자 살만해?", "삼성전자에 투자해도 될까?",
    "지금 구매해도 될까?",
])
def test_same_intent_about_supported_company_is_outlook(question):
    plan = classify(question, SAMSUNG, COMPANIES)
    assert plan.kind == DATA and plan.question_type == OUTLOOK
    assert tools_of(plan) == sorted([(QUOTE_TOOL, "005930"), (NEWS_TOOL, "005930")])


@pytest.mark.parametrize("question", ["노트북을 구매해도될까?", "아이폰 살까?", "가방 구입할까?", "운동화 사고 싶어", "회사 어때?"])
def test_buying_other_things_is_out_of_scope(question):
    assert classify(question, SAMSUNG, COMPANIES).kind == OUT_OF_SCOPE


def test_company_questions_are_not_buy_questions():
    assert classify("삼성전자는 어떤 회사야?", SAMSUNG, COMPANIES).question_type != OUTLOOK
    assert classify("구매력이 뭐야?", SAMSUNG, COMPANIES).kind in (CONCEPT, OUT_OF_SCOPE)


def test_compare_without_metric_asks_again():
    plan = classify("삼성전자와 SK하이닉스 비교해줘", SAMSUNG, COMPANIES)
    assert plan.kind == NEED_MORE_INFO and "지표" in plan.message
    history = [{"role": "user", "content": "삼성전자와 SK하이닉스 비교해줘"}, {"role": "assistant", "content": plan.message}]
    answered = classify("매출로", SAMSUNG, COMPANIES, history)
    assert tools_of(answered) == sorted([(FINANCIAL_TOOL, "005930"), (FINANCIAL_TOOL, "000660")])


def test_year_over_year_is_not_last_year():
    assert classify("삼성전자 실적 전년 대비 어때?", SAMSUNG, COMPANIES).calls[0].year is None
    assert classify("삼성전자 전년 동기 대비 매출", SAMSUNG, COMPANIES).calls[0].year is None
    assert classify("삼성전자 작년 실적 전년 대비 어때?", SAMSUNG, COMPANIES).calls[0].year == str(datetime.now(KST).year - 1)


def test_alias_is_recognized():
    plan = classify("하이닉스 뉴스 보여줘", SAMSUNG, COMPANIES)
    assert tools_of(plan) == [(NEWS_TOOL, "000660")]


@pytest.mark.parametrize("question, symbol", [
    ("현대차 주가 알려줘", "005380"), ("현대자동차 현재가", "005380"), ("네이버 실적 알려줘", "035420"), ("NAVER 주가", "035420"),
    ("카카오 현재가", "035720"), ("기아 주가 알려줘", "000270"), ("기아는 얼마야?", "000270"), ("기아차 실적", "000270"),
    ("LG화학 영업이익 알려줘", "051910"), ("엘지화학 주가", "051910"), ("삼성SDI 주가 알려줘", "006400"), ("삼성sdi 실적", "006400"),
    ("삼성에스디아이 뉴스", "006400"), ("삼성전자 현재가", "005930"), ("삼성 주가", "005930"),
])
def test_added_stocks_are_recognized(question, symbol):
    plan = classify(question, SAMSUNG, ALL_COMPANIES)
    assert plan.kind == DATA, question
    assert {c.symbol for c in plan.calls} == {symbol}, question


@pytest.mark.parametrize("text, symbols", [
    ("삼성SDI와 삼성전자", ["006400", "005930"]), ("카카오랑 네이버", ["035720", "035420"]), ("아기아빠 이야기아냐", []), ("nokia 소식", []),
])
def test_find_companies_keeps_order_and_ignores_word_parts(text, symbols):
    assert [c.symbol for c in find_companies(text, ALL_COMPANIES)] == symbols


@pytest.mark.parametrize("question, word", [
    ("카카오뱅크 주가 알려줘", "카카오뱅크"), ("현대건설 실적", "현대건설"), ("LG전자 주가", "LG전자"), ("현대 주가 알려줘", "현대"), ("LG 실적 알려줘", "LG"),
])
def test_related_companies_are_not_added_stocks(question, word):
    plan = classify(question, SAMSUNG, ALL_COMPANIES)
    assert plan.kind == NEED_MORE_INFO and plan.calls == []
    assert word in plan.message


def test_compare_added_stocks():
    plan = classify("현대차와 기아 영업이익 비교해줘", SAMSUNG, ALL_COMPANIES)
    assert tools_of(plan) == sorted([(FINANCIAL_TOOL, "005380"), (FINANCIAL_TOOL, "000270")])


def test_out_of_scope_has_no_llm():
    for q in ["오늘 점심 뭐 먹지?", "파이썬 for문 알려줘", "내일 날씨 어때?"]:
        plan = classify(q, SAMSUNG, COMPANIES)
        assert plan.kind == OUT_OF_SCOPE, q
        assert plan.message


def test_unsupported_company_asks_again():
    plan = classify("현대모비스 주가 알려줘", SAMSUNG, COMPANIES)
    assert plan.kind == NEED_MORE_INFO
    assert "현대모비스" in plan.message


def test_finance_words_are_not_company_names():
    plan = classify("매출과 영업이익 알려줘", SAMSUNG, COMPANIES)
    assert plan.kind == DATA
    assert tools_of(plan) == [(FINANCIAL_TOOL, "005930")]


def test_compare_needs_two_companies():
    assert classify("삼성전자랑 비교해줘", SAMSUNG, COMPANIES).kind == NEED_MORE_INFO
    plan = classify("삼성전자랑 하이닉스 영업이익 비교해줘", SAMSUNG, COMPANIES)
    assert tools_of(plan) == sorted([(FINANCIAL_TOOL, "005930"), (FINANCIAL_TOOL, "000660")])


def test_financial_period_is_passed_to_tool():
    last_year = str(datetime.now(KST).year - 1)
    call = classify("삼성전자 작년 실적", SAMSUNG, COMPANIES).calls[0]
    assert (call.year, call.report) == (last_year, None)
    call = classify("삼성전자 2024년 반기 매출", SAMSUNG, COMPANIES).calls[0]
    assert (call.year, call.report) == ("2024", "11012")


def test_company_info():
    plan = classify("삼성전자는 어떤 회사야?", SAMSUNG, COMPANIES)
    assert (INFO_TOOL, "005930") in tools_of(plan)


def test_follow_up_inherits_previous_question():
    history = [{"role": "user", "content": "삼성전자 최근 실적 알려줘"}, {"role": "assistant", "content": "..."}]
    plan = classify("그럼 작년은?", SAMSUNG, COMPANIES, history)
    assert tools_of(plan) == [(FINANCIAL_TOOL, "005930")]
    assert plan.calls[0].year == str(datetime.now(KST).year - 1)

    plan = classify("하이닉스는?", SAMSUNG, COMPANIES, history)
    assert tools_of(plan) == [(FINANCIAL_TOOL, "000660")]


# 연도 없이 분기만 물어도 그 분기 보고서를 찾는다 (Spring 이 연도를 찾는다)
def test_quarter_without_year_keeps_report():
    plan = classify("삼성전자 1분기 실적 알려줘", SAMSUNG, COMPANIES)
    assert [(c.name, c.year, c.report) for c in plan.calls] == [(FINANCIAL_TOOL, None, "11013")]


# '삼성'으로 시작하는 다른 회사는 삼성전자로 조회하지 않는다
@pytest.mark.parametrize("question, word", [
    ("삼성카드 주가 알려줘", "삼성카드"),
    ("삼성카드는 얼마야?", "삼성카드"),
    ("삼성엔지니어링 실적 알려줘", "삼성엔지니어링"),
    ("삼성에스디에스 주가", "삼성에스디에스"),
    ("삼성자산운용 실적 어때?", "삼성자산운용"),
])
def test_other_samsung_company_is_not_supported(question, word):
    plan = classify(question, HYNIX, COMPANIES)
    assert plan.kind == NEED_MORE_INFO and plan.calls == []
    assert f"'{word}'" in plan.message


@pytest.mark.parametrize("question, expected", [
    ("삼성 주가 알려줘", QUOTE_TOOL),
    ("삼성주가 알려줘", QUOTE_TOOL),
    ("삼성의 실적 알려줘", FINANCIAL_TOOL),
    ("삼성은 얼마야?", QUOTE_TOOL),
    ("삼성전자 주가 알려줘", QUOTE_TOOL),
])
def test_samsung_alias_still_works(question, expected):
    plan = classify(question, HYNIX, COMPANIES)
    assert tools_of(plan) == [(expected, "005930")]


def test_samsung_word_unrelated_to_stock_is_out_of_scope():
    assert classify("삼성동 맛집 추천해줘", SAMSUNG, COMPANIES).kind == OUT_OF_SCOPE


# 조회 Tool 이 없는 값은 종목 시세로 대신 답하지 않는다
@pytest.mark.parametrize("question", ["코스피 지수 얼마야?", "달러 환율 얼마야?", "금리 얼마야?", "코스닥 지수 등락 알려줘"])
def test_market_value_has_no_tool(question):
    plan = classify(question, SAMSUNG, COMPANIES)
    assert plan.kind == NO_DATA and plan.calls == []
    assert "삼성전자" in plan.message


def test_market_concept_is_still_explained():
    assert classify("환율이 뭐야?", SAMSUNG, COMPANIES).kind == CONCEPT
    assert classify("코스피 지수가 뭐야?", SAMSUNG, COMPANIES).kind == CONCEPT


def test_stock_price_with_market_word_is_still_quote():
    plan = classify("삼성전자 환율 영향으로 주가 얼마야?", SAMSUNG, COMPANIES)
    assert (QUOTE_TOOL, "005930") in tools_of(plan)


# 기업을 말하지 않은 개념 질문은 실적을 조회하지 않는다
@pytest.mark.parametrize("question", [
    "영업이익 계산법 알려줘",
    "영업이익률 계산 방법 알려줘",
    "매출 구하는 법 알려줘",
    "부채비율 높으면 안 좋은 거야?",
    "자본 낮으면 위험해?",
    "매출이랑 영업이익 차이 알려줘",
])
def test_concept_without_company_has_no_tools(question):
    plan = classify(question, SAMSUNG, COMPANIES)
    assert plan.kind == CONCEPT and plan.calls == []


def test_company_financial_question_still_uses_tool():
    assert tools_of(classify("삼성전자 부채비율 알려줘", SAMSUNG, COMPANIES)) == [(FINANCIAL_TOOL, "005930")]
    assert tools_of(classify("실적 알려줘", SAMSUNG, COMPANIES)) == [(FINANCIAL_TOOL, "005930")]


def test_out_of_scope_message_is_one_sentence():
    plan = classify("오늘 점심 뭐 먹지?", SAMSUNG, COMPANIES)
    assert plan.kind == OUT_OF_SCOPE and plan.message.count(".") == 1
