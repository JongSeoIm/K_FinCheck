"""질문 해석 보조: OpenAI 는 해석만 하고, 값 검사와 Tool 선택은 서버가 한다 (OpenAI 는 가짜로 바꾼다)"""

import asyncio
from datetime import datetime

import pytest

import interpreter
import llm
from classifier import (COMPARE, CONCEPT, DATA, FINANCIAL, FINANCIAL_TOOL, INFO_TOOL, KST, NEED_MORE_INFO, NEWS_TOOL, OUT_OF_SCOPE,
                        OUTLOOK, QUOTE_TOOL, Company, classify)
from interpreter import Interpretation

SAMSUNG = Company("005930", "삼성전자", "KOSPI")
HYNIX = Company("000660", "SK하이닉스", "KOSPI")
COMPANIES = [SAMSUNG, HYNIX]


def tools_of(plan):
    return sorted((c.name, c.symbol) for c in plan.calls)


@pytest.mark.parametrize("question", ["이 회사 돈 잘 벌어?", "쏘카 사도 돼?", "삼성전자 어때?", "주식 처음인데 뭐부터 봐야 해?", "안녕"])
def test_ambiguous_rule_results_are_not_confident(question):
    assert classify(question, SAMSUNG, COMPANIES).confident is False


@pytest.mark.parametrize("question", ["부채비율이 뭐야?", "삼성전자 현재가 알려줘", "삼성전자 최근 실적 알려줘", "삼성전자 왜 올랐어?",
                                      "오늘 점심 뭐 먹지?", "카카오 주가 알려줘", "코스피 지수 얼마야?", "삼성카드 주가 알려줘"])
def test_clear_rule_results_are_confident(question):
    assert classify(question, SAMSUNG, COMPANIES).confident is True


@pytest.fixture
def stub(monkeypatch):
    state = {"calls": 0, "result": None, "error": False}

    async def fake_parse(instructions, messages, schema):
        state["calls"] += 1
        state["messages"] = messages
        if state["error"]:
            raise llm.LlmError("실패")
        return state["result"]

    monkeypatch.setattr(llm, "parse", fake_parse)
    monkeypatch.setattr(llm, "is_configured", lambda: True)
    return state


def refine(question, history=None):
    plan = classify(question, SAMSUNG, COMPANIES, history)
    return asyncio.run(interpreter.refine(question, plan, SAMSUNG, COMPANIES, history or []))


def test_clear_question_does_not_call_openai(stub):
    plan = refine("삼성전자 현재가 알려줘")
    assert stub["calls"] == 0
    assert tools_of(plan) == [(QUOTE_TOOL, "005930")]


def test_paraphrase_becomes_financial_question(stub):
    stub["result"] = Interpretation(question_type="financial")
    plan = refine("이 회사 돈 잘 벌어?")
    assert stub["calls"] == 1
    assert plan.kind == DATA and plan.question_type == FINANCIAL
    assert tools_of(plan) == [(FINANCIAL_TOOL, "005930")]


def test_other_company_is_not_looked_up(stub):
    stub["result"] = Interpretation(question_type="outlook", other_company="쏘카")
    plan = refine("쏘카 사도 돼?")
    assert plan.kind == NEED_MORE_INFO and plan.calls == []
    assert "쏘카" in plan.message


def test_outlook_uses_quote_and_news(stub):
    stub["result"] = Interpretation(question_type="outlook", symbols=["000660"])
    plan = refine("하이닉스 지금 들어갈 타이밍이야?")
    assert plan.question_type == OUTLOOK
    assert tools_of(plan) == sorted([(QUOTE_TOOL, "000660"), (NEWS_TOOL, "000660")])


def test_out_of_scope_and_concept(stub):
    stub["result"] = Interpretation(question_type="out_of_scope")
    assert refine("안녕").kind == OUT_OF_SCOPE
    stub["result"] = Interpretation(question_type="term")
    assert refine("주식 처음인데 뭐부터 봐야 해?").kind == CONCEPT


def to_plan(result):
    fallback = classify("안녕", SAMSUNG, COMPANIES)
    return interpreter.to_plan(result, fallback, SAMSUNG, COMPANIES)


def test_values_from_openai_are_checked():
    plan = to_plan(Interpretation(question_type="financial", symbols=["999999"], year=1990, report="q1"))
    assert tools_of(plan) == [(FINANCIAL_TOOL, "005930")]  # 없는 코드는 버리고 대화창 종목
    assert plan.calls[0].year is None and plan.calls[0].report == "11013"

    this_year = datetime.now(KST).year
    plan = to_plan(Interpretation(question_type="financial", symbols=["000660"], year=this_year - 1, report="annual"))
    assert (plan.calls[0].symbol, plan.calls[0].year, plan.calls[0].report) == ("000660", str(this_year - 1), "11011")
    assert to_plan(Interpretation(question_type="financial", year=this_year + 3)).calls[0].year is None


def test_company_and_compare_rules():
    assert tools_of(to_plan(Interpretation(question_type="company"))) == [(INFO_TOOL, "005930")]

    plan = to_plan(Interpretation(question_type="compare", symbols=["005930"]))
    assert plan.kind == NEED_MORE_INFO and plan.question_type == COMPARE
    plan = to_plan(Interpretation(question_type="compare", symbols=["005930", "000660"]))
    assert plan.kind == NEED_MORE_INFO
    plan = to_plan(Interpretation(question_type="compare", symbols=["005930", "000660"], compare_metric="financial"))
    assert plan.question_type == COMPARE
    assert tools_of(plan) == sorted([(FINANCIAL_TOOL, "005930"), (FINANCIAL_TOOL, "000660")])


def test_unclear_keeps_rule_result():
    fallback = classify("안녕", SAMSUNG, COMPANIES)
    assert interpreter.to_plan(Interpretation(question_type="unclear"), fallback, SAMSUNG, COMPANIES) is fallback


def test_failure_keeps_rule_result(stub):
    stub["error"] = True
    plan = refine("안녕")
    assert stub["calls"] == 1 and plan.kind == OUT_OF_SCOPE


def test_input_contains_supported_stocks_and_recent_history(stub):
    stub["result"] = Interpretation(question_type="term")
    history = [{"role": "user", "content": f"질문 {i}"} for i in range(6)]
    refine("안녕", history)
    text = stub["messages"][0]["content"]
    assert "삼성전자(005930)" in text and "SK하이닉스(000660)" in text
    assert "질문 5" in text and "질문 1" not in text
