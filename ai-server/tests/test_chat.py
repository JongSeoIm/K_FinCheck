"""/chat 흐름 테스트 (Spring·OpenAI 는 가짜로 바꾼다)"""

from datetime import datetime

import pytest
from fastapi.testclient import TestClient

import classifier
import config
import interpreter
import llm
import main
import prompts
import tools
from tools import ToolResult

COMPANIES = [classifier.Company("005930", "삼성전자", "KOSPI"), classifier.Company("000660", "SK하이닉스", "KOSPI")]

QUOTE = {"symbol": "005930", "price": 222000, "changeAmount": -7000, "changeRate": -3.06, "source": "KIS",
         "eventTime": "2026-11-04T10:31:05+09:00", "cumulativeVolume": 13391670}
FINANCIAL = {"symbol": "005930", "bsnsYear": "2026", "reportName": "3분기보고서", "fsName": "연결",
             "url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261114000001",
             "incomeBasis": "해당 분기 3개월 금액, 전기는 전년 같은 분기", "balanceBasis": "...",
             "items": [{"accountName": "매출액", "statement": "IS", "current": 86_000_000_000_000, "previous": 79_000_000_000_000}]}
DISCLOSURES = [{"rceptNo": "20261105000001", "reportName": "주요사항보고서(자기주식취득결정)", "filedAt": "2026-11-05", "submitter": "삼성전자",
                "url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261105000001"}]


@pytest.fixture
def client(monkeypatch):
    async def fake_companies():
        return COMPANIES

    calls = {"llm": 0, "messages": None}

    async def fake_generate(instructions, messages):
        calls["llm"] += 1
        calls["messages"] = messages
        return "**삼성전자** 현재가는 222,000원이에요.\n- 체결 시각 10시 31분"

    monkeypatch.setattr(tools, "list_companies", fake_companies)
    monkeypatch.setattr(llm, "generate", fake_generate)

    async def no_interpretation(question, plan, current, companies, history):
        return plan

    monkeypatch.setattr(interpreter, "refine", no_interpretation)
    monkeypatch.setattr(config, "AI_INTERNAL_KEY", "")
    c = TestClient(main.app)
    c.calls = calls
    return c


def fake_tools(monkeypatch, fail=()):
    data = {"get_stock_quote": QUOTE, "get_financial_result": FINANCIAL, "get_company_disclosures": DISCLOSURES,
            "get_company_info": {"symbol": "005930", "name": "삼성전자", "market": "KOSPI"}}

    async def run(calls):
        return [ToolResult(c.name, c.symbol, False, error="5초 안에 응답이 없습니다.") if c.name in fail
                else ToolResult(c.name, c.symbol, True, data[c.name]) for c in calls]

    monkeypatch.setattr(tools, "run_tools", run)


def ask(client, question, history=None):
    return client.post("/chat", json={"question": question, "symbol": "005930", "stockName": "삼성전자",
                                      "history": history or []}).json()


def test_quote_answer_has_kis_source(client, monkeypatch):
    fake_tools(monkeypatch)
    res = ask(client, "삼성전자 현재가 알려줘")
    assert res["status"] == "answered"
    assert "**" not in res["answer"] and "- " not in res["answer"]   # Markdown 정리
    assert res["sources"] == [{"provider": "KIS", "label": "삼성전자 현재가",
                               "basisAt": "2026-11-04T10:31:05+09:00", "url": None}]


def test_financial_source_has_year_report_and_basis(client, monkeypatch):
    fake_tools(monkeypatch)
    res = ask(client, "삼성전자 최근 실적 알려줘")
    source = res["sources"][0]
    assert source["provider"] == "OpenDART"
    assert source["label"] == "삼성전자 2026년 3분기보고서 (연결)"
    assert source["url"].startswith("https://dart.fss.or.kr/")
    # LLM 에 넘긴 자료에 억 단위 금액과 전년 대비 증감률이 들어간다
    assert "86조 원" in client.calls["messages"][-1]["content"]
    assert "+8.9%" in client.calls["messages"][-1]["content"]


def test_quote_without_trade_time_says_query_time(client, monkeypatch):
    quote = {**QUOTE, "eventTime": None, "receivedAt": "2026-11-04T15:38:54.123+09:00"}

    async def run(calls):
        return [ToolResult(c.name, c.symbol, True, quote) for c in calls]

    monkeypatch.setattr(tools, "run_tools", run)
    source = ask(client, "삼성전자 현재가 알려줘")["sources"][0]
    assert source["label"] == "삼성전자 현재가 (조회 시각 기준)"
    assert source["basisAt"] == quote["receivedAt"]
    sent = client.calls["messages"][-1]["content"]
    assert '"체결시각": null' in sent and '"조회시각": "2026-11-04T15:38:54.123+09:00"' in sent


def test_asked_year_and_periods_are_given_to_llm(client, monkeypatch):
    fake_tools(monkeypatch)
    history = [{"role": "user", "content": "삼성전자 최근 실적 알려줘"}, {"role": "assistant", "content": "..."}]
    ask(client, "그럼 작년은?", history)
    sent = client.calls["messages"][-1]["content"]
    assert "[기간 기준]" in sent and "당기기간" in sent and "전기기간" in sent
    # 사업연도를 지정한 질문은 전기 금액을 넘기지 않아 그 전해 금액으로 답할 수 없다 (증감률은 넘긴다)
    assert "86조 원" in sent and "79조 원" not in sent and "+8.9%" in sent
    # LLM 에는 "작년"이 아니라 서버가 해석한 실제 연도로 질문을 전달한다
    assert f"[질문] 그럼 {datetime.now(classifier.KST).year - 1}년은?" in sent


def test_number_not_in_data_is_retried_then_hidden(client, monkeypatch):
    fake_tools(monkeypatch)
    answers = ["매출은 86조 원이고 전년 대비 16.2% 늘었어요.", "매출은 86조 원이고 전년 대비 16.2% 늘었어요."]

    async def generate(instructions, messages):
        client.calls["llm"] += 1
        return answers.pop(0)

    monkeypatch.setattr(llm, "generate", generate)
    res = ask(client, "삼성전자 최근 실적 알려줘")
    assert client.calls["llm"] == 2
    assert res["status"] == "unavailable" and "16.2" not in res["answer"] and res["sources"] == []


def test_retry_with_verified_numbers_is_answered(client, monkeypatch):
    fake_tools(monkeypatch)
    answers = ["매출은 전년 대비 16.2% 늘었어요.", "매출은 86조 원으로 전년 대비 8.9% 늘었어요."]

    async def generate(instructions, messages):
        return answers.pop(0)

    monkeypatch.setattr(llm, "generate", generate)
    res = ask(client, "삼성전자 최근 실적 알려줘")
    assert res["status"] == "answered" and "8.9%" in res["answer"]


def test_unsupported_company_has_no_tool_or_sources(client, monkeypatch):
    fake_tools(monkeypatch)
    res = ask(client, "테슬라 사도 돼?")
    assert res["status"] == "need_more_info" and res["sources"] == []
    assert client.calls["llm"] == 0


def test_every_disclosure_given_to_llm_has_source(client, monkeypatch):
    items = [{**DISCLOSURES[0], "reportName": f"공시{i}", "url": f"https://dart.example/{i}"} for i in range(5)]

    async def run(calls):
        return [ToolResult(c.name, c.symbol, True, items) for c in calls]

    monkeypatch.setattr(tools, "run_tools", run)
    assert [s["url"] for s in ask(client, "삼성전자 뉴스 알려줘")["sources"]] == [i["url"] for i in items]


def test_no_disclosure_is_no_data():
    import asyncio

    async def fake_get(client, path, params=None):
        return {"disclosures": []}

    call = classifier.ToolCall(classifier.DISCLOSURE_TOOL, "005930", company="삼성전자")
    original, tools._get = tools._get, fake_get
    try:
        with pytest.raises(tools.ToolError) as error:
            asyncio.run(tools.get_company_disclosures(None, call))
    finally:
        tools._get = original
    assert error.value.no_data and "삼성전자" in str(error.value)


def test_naver_news_is_not_called_by_ai(client, monkeypatch):
    assert not hasattr(tools, "get_company_news")
    urls = []

    async def fake_get(_client, path, params=None):
        urls.append(path)
        if path.endswith("/disclosures"):
            return {"disclosures": DISCLOSURES}
        return {"quote": QUOTE}

    monkeypatch.setattr(tools, "_get", fake_get)
    for question in ["삼성전자 관련 뉴스 알려줘", "삼성전자 왜 올랐어?", "삼성전자 사도 돼?"]:
        ask(client, question)
    assert urls and not any("/news" in path for path in urls)


def test_disclosure_source_has_date_and_link(client, monkeypatch):
    fake_tools(monkeypatch)
    source = ask(client, "삼성전자 뉴스 알려줘")["sources"][0]
    assert source["provider"] == prompts.DISCLOSURE_PROVIDER
    assert source["url"] == DISCLOSURES[0]["url"]
    assert source["basisAt"] == "2026-11-05"


def test_concept_has_no_sources(client, monkeypatch):
    fake_tools(monkeypatch)
    res = ask(client, "부채비율이 뭐야?")
    assert res["status"] == "answered"
    assert res["sources"] == []


def test_out_of_scope_does_not_call_llm(client, monkeypatch):
    fake_tools(monkeypatch)
    res = ask(client, "오늘 점심 뭐 먹지?")
    assert res["status"] == "out_of_scope"
    assert client.calls["llm"] == 0


def test_partial_failure_uses_only_successful_data(client, monkeypatch):
    fake_tools(monkeypatch, fail={"get_company_disclosures"})
    res = ask(client, "삼성전자 왜 떨어졌어?")
    assert res["status"] == "answered"
    assert [s["provider"] for s in res["sources"]] == ["KIS"]
    assert "[조회 실패]" in client.calls["messages"][-1]["content"]


def test_all_tools_failed_does_not_call_llm(client, monkeypatch):
    fake_tools(monkeypatch, fail={"get_stock_quote"})
    res = ask(client, "삼성전자 현재가")
    assert res["status"] == "unavailable"
    assert client.calls["llm"] == 0


def test_history_is_sent_to_llm(client, monkeypatch):
    fake_tools(monkeypatch)
    history = [{"role": "user", "content": f"질문{i}"} if i % 2 == 0 else {"role": "assistant", "content": f"답변{i}"}
               for i in range(12)]
    ask(client, "삼성전자 현재가", history)
    sent = client.calls["messages"]
    assert len(sent) == 11   # 최근 10개 + 이번 질문
    assert sent[0]["content"] == "질문2"


def test_internal_key(client, monkeypatch):
    fake_tools(monkeypatch)
    monkeypatch.setattr(config, "AI_INTERNAL_KEY", "secret")
    assert client.post("/chat", json={"question": "삼성전자 현재가", "symbol": "005930"}).status_code == 403
    ok = client.post("/chat", json={"question": "삼성전자 현재가", "symbol": "005930"}, headers={"X-Internal-Key": "secret"})
    assert ok.status_code == 200


def fake_no_data(monkeypatch, errors):
    async def run(calls):
        return [ToolResult(c.name, c.symbol, False, error=errors[i], no_data=True) for i, c in enumerate(calls)]

    monkeypatch.setattr(tools, "run_tools", run)


def test_no_data_explains_what_is_missing(client, monkeypatch):
    fake_no_data(monkeypatch, ["삼성전자의 2020년 공시 실적을 찾지 못했습니다."])
    body = ask(client, "삼성전자 2020년 실적 알려줘")
    assert body["status"] == "unavailable"
    assert "2020년 공시 실적을 찾지 못했습니다" in body["answer"] and "최근 실적" in body["answer"]
    assert "잠시 후" not in body["answer"]
    assert client.calls["llm"] == 0


def test_tool_outage_still_says_try_later(client, monkeypatch):
    fake_tools(monkeypatch, fail=("get_financial_result",))
    body = ask(client, "삼성전자 최근 실적 알려줘")
    assert body["status"] == "unavailable" and "잠시 후" in body["answer"]
    assert client.calls["llm"] == 0


def test_market_value_question_does_not_call_tools(client, monkeypatch):
    async def run(calls):
        raise AssertionError("Tool 을 부르면 안 된다")

    monkeypatch.setattr(tools, "run_tools", run)
    body = ask(client, "코스피 지수 얼마야?")
    assert body["status"] == "unavailable" and body["sources"] == []
    assert "코스피" in body["answer"]
    assert client.calls["llm"] == 0


def test_visuals_are_built_from_tool_results(client, monkeypatch):
    fake_tools(monkeypatch)
    body = ask(client, "삼성전자 현재가 알려줘")
    assert [v["type"] for v in body["visuals"]] == ["quote"]
    assert body["visuals"][0]["price"] == 222000 and body["visuals"][0]["eventTime"] == QUOTE["eventTime"]

    body = ask(client, "삼성전자 관련 뉴스 알려줘")
    assert body["visuals"][0]["type"] == "disclosure"
    assert body["visuals"][0]["items"][0]["url"] == DISCLOSURES[0]["url"]


def test_concept_answer_has_no_visuals(client, monkeypatch):
    fake_tools(monkeypatch)
    assert ask(client, "부채비율이 뭐야?")["visuals"] == []


def test_earning_compare_labels():
    assert prompts._earning_compare(120, 100) == (20.0, None)
    assert prompts._earning_compare(50, -10) == (None, "흑자 전환")
    assert prompts._earning_compare(-50, 10) == (None, "적자 전환")
    assert prompts._earning_compare(-50, -10) == (None, "적자 지속")
    assert prompts._earning_compare(None, 10) == (None, "비교 불가")
    assert prompts._earning_compare(10, 0) == (None, "비교 불가")


def test_financial_visual_has_rows_and_link():
    result = ToolResult("get_financial_result", "005930", True, {
        "bsnsYear": "2025", "reprtCode": "11011", "reportName": "사업보고서", "fsName": "연결", "url": "https://dart.example",
        "items": [{"accountId": "REVENUE", "accountName": "매출액", "statement": "IS", "current": 120, "previous": 100},
                  {"accountId": "TOTAL_ASSETS", "accountName": "자산총계", "statement": "BS", "current": 1, "previous": 1}]})
    visual = prompts.build_visuals([result], COMPANIES)[0]
    assert visual["url"] == "https://dart.example" and visual["period"] == "2025년 1년"
    assert [r["code"] for r in visual["rows"]] == ["REVENUE"]
    assert visual["rows"][0]["changeRate"] == 20.0


def test_failed_tool_has_no_visual():
    assert prompts.build_visuals([ToolResult("get_stock_quote", "005930", False, error="실패")], COMPANIES) == []


def test_news_rule_is_in_instructions():
    assert "뉴스 기사는 조회하지 않는다" in prompts.SYSTEM_PROMPT
    assert "관련 뉴스 패널" in prompts.SYSTEM_PROMPT


def _financial_with_metrics():
    return {**FINANCIAL, "metrics": [
        {"code": "OPERATING_MARGIN", "name": "영업이익률", "value": 4.23, "status": "OK"},
        {"code": "DEBT_RATIO", "name": "부채비율", "value": None, "status": "NOT_CALCULABLE"},
        {"code": "CURRENT_RATIO", "name": "유동비율", "value": None, "status": "MISSING"},
    ]}


def test_financial_evidence_includes_metrics_from_spring():
    plan = classifier.classify("삼성전자 영업이익률 알려줘", COMPANIES[0], COMPANIES)
    result = ToolResult(classifier.FINANCIAL_TOOL, "005930", True, _financial_with_metrics())
    evidence = prompts._evidence(result, "삼성전자")
    assert {"지표": "영업이익률", "값": "4.23%"} in evidence["재무지표"]
    assert {"지표": "부채비율", "상태": "계산 불가"} in evidence["재무지표"]
    assert {"지표": "유동비율", "상태": "자료 없음"} in evidence["재무지표"]
    assert prompts.unverified_numbers("영업이익률은 4.23%입니다.", plan, [result], COMPANIES) == []
    assert prompts.unverified_numbers("영업이익률은 9.99%입니다.", plan, [result], COMPANIES) == ["9.99%"]


@pytest.mark.parametrize("question", ["삼성전자 영업이익률 어떻게 돼?", "삼성전자 부채비율 알려줘", "삼성전자 유동비율 알려줘"])
def test_metric_questions_call_financial_tool(question):
    plan = classifier.classify(question, COMPANIES[0], COMPANIES)
    assert [c.name for c in plan.calls] == [classifier.FINANCIAL_TOOL]
