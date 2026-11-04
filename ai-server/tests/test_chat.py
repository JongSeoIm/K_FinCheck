"""/chat 흐름 테스트 (Spring·OpenAI 는 가짜로 바꾼다)"""

from datetime import datetime

import pytest
from fastapi.testclient import TestClient

import classifier
import config
import llm
import main
import tools
from tools import ToolResult

COMPANIES = [classifier.Company("005930", "삼성전자", "KOSPI"), classifier.Company("000660", "SK하이닉스", "KOSPI")]

QUOTE = {"symbol": "005930", "price": 222000, "changeAmount": -7000, "changeRate": -3.06, "source": "KIS",
         "eventTime": "2026-11-04T10:31:05+09:00", "cumulativeVolume": 13391670}
FINANCIAL = {"symbol": "005930", "bsnsYear": "2026", "reportName": "3분기보고서", "fsName": "연결",
             "url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261114000001",
             "incomeBasis": "해당 분기 3개월 금액, 전기는 전년 같은 분기", "balanceBasis": "...",
             "items": [{"accountName": "매출액", "statement": "IS", "current": 86_000_000_000_000, "previous": 79_000_000_000_000}]}
NEWS = [{"headline": "삼성전자 HBM 공급 확대", "summary": "요약", "source": "연합뉴스", "datetime": 1793750000,
         "url": "https://news.example/1", "related": True}]


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
    monkeypatch.setattr(config, "AI_INTERNAL_KEY", "")
    c = TestClient(main.app)
    c.calls = calls
    return c


def fake_tools(monkeypatch, fail=()):
    data = {"get_stock_quote": QUOTE, "get_financial_result": FINANCIAL, "get_company_news": NEWS,
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


def test_every_article_given_to_llm_has_source(client, monkeypatch):
    articles = [{**NEWS[0], "headline": f"기사{i}", "url": f"https://news.example/{i}"} for i in range(5)]

    async def run(calls):
        return [ToolResult(c.name, c.symbol, True, articles) for c in calls]

    monkeypatch.setattr(tools, "run_tools", run)
    assert [s["label"] for s in ask(client, "삼성전자 뉴스 알려줘")["sources"]] == [a["headline"] for a in articles]


def test_news_tool_prefers_headline_and_drops_unrelated():
    import asyncio

    news = [{"headline": "코스피 마감", "related": True}, {"headline": "무관한 기사", "related": False},
            {"headline": "삼성전자 신제품", "related": True}]

    async def fake_get(client, path, params=None):
        return {"news": news}

    call = classifier.ToolCall(classifier.NEWS_TOOL, "005930", company="삼성전자")
    original, tools._get = tools._get, fake_get
    try:
        picked = asyncio.run(tools.get_company_news(None, call))
    finally:
        tools._get = original
    assert [n["headline"] for n in picked] == ["삼성전자 신제품", "코스피 마감"]


def test_news_source_has_time_and_link(client, monkeypatch):
    fake_tools(monkeypatch)
    source = ask(client, "삼성전자 뉴스 알려줘")["sources"][0]
    assert source["provider"] == "NAVER API HUB"
    assert source["url"] == "https://news.example/1"
    assert source["basisAt"].startswith("2026-")


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
    fake_tools(monkeypatch, fail={"get_company_news"})
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
