import asyncio

import httpx

from app.chat import drop_directive_sentences, handle, strip_markdown

PRICE = {"price": 209500, "priceChange": -10000, "change": -4.56, "open": 226500, "high": 228000,
         "low": 208000, "prevClose": 219500, "updatedAt": "2026-11-04T04:39:10Z"}
EARN = {"businessYear": 2025, "previousYear": 2024, "basis": "연결", "unit": "원", "dartUrl": "https://dart.fss.or.kr/x",
        "lines": [{"key": "REVENUE", "label": "매출액", "current": 300870903000000, "previous": 258935494000000,
                   "changePercent": 16.2, "status": "NORMAL"}]}
NEWS = [{"id": "1", "headline": "삼성전자 <b>신제품</b> 공개", "summary": "요약", "source": "n",
         "datetime": 1793770000, "url": "https://news.example/1"}]


def make_client(fail=()):
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        calls.append(path)
        for key in fail:
            if key in path:
                return httpx.Response(502, json={"error": "x"})
        if "/trade-time/" in path:
            return httpx.Response(200, json={"tradedAt": "2026-11-04T14:08:49+09:00", "tradeTime": "14:08:49"})
        if "/price/" in path:
            return httpx.Response(200, json=PRICE)
        if "/earnings/" in path:
            return httpx.Response(200, json=EARN)
        if "/news/" in path:
            return httpx.Response(200, json=NEWS)
        return httpx.Response(404)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler)), calls


class FakeLlm:
    def __init__(self, text="삼성전자는 상승했어요."):
        self.text, self.messages = text, None
        self.called = 0

    async def __call__(self, client, messages):
        self.called += 1
        self.messages = messages
        return self.text


def run(coro):
    return asyncio.run(coro)


def test_out_of_scope_skips_llm_and_tools():
    client, calls = make_client()
    llm = FakeLlm()
    out = run(handle("오늘 점심 뭐 먹지", [], None, client, llm))
    assert out.status == "out_of_scope" and llm.called == 0 and not calls


def test_concept_has_no_tool_call_and_no_sources():
    client, calls = make_client()
    llm = FakeLlm("부채비율은 자본 대비 부채 비율이에요.")
    out = run(handle("부채비율이 뭐야?", [], None, client, llm))
    assert out.status == "answered" and not calls and out.sources == [] and llm.called == 1


def test_quote_uses_tool_and_returns_source():
    client, calls = make_client()
    llm = FakeLlm()
    out = run(handle("삼성전자 현재가 알려줘", [], None, client, llm))
    assert out.status == "answered" and sorted(calls) == ["/api/kis/price/005930", "/api/kis/trade-time/005930"]
    assert out.sources[0]["provider"] == "KIS" and out.sources[0]["basisAt"] == "최근 체결 2026-11-04 14:08 KST"
    assert "최근 체결 시각 2026-11-04 14:08 KST" in llm.messages[-1]["content"]
    assert "209,500원" in llm.messages[-1]["content"]


def test_trade_time_failure_falls_back_to_query_time():
    client, _ = make_client(fail=("/trade-time/",))
    llm = FakeLlm()
    out = run(handle("삼성전자 현재가 알려줘", [], None, client, llm))
    assert out.status == "answered" and out.sources[0]["basisAt"].endswith("조회")
    assert "최근 체결 시각은 확인하지 못했다" in llm.messages[-1]["content"]


def test_why_moved_calls_quote_and_news():
    client, calls = make_client()
    out = run(handle("삼성전자 왜 올랐어?", [], None, client, FakeLlm()))
    assert sorted(set(calls)) == ["/api/kis/news/005930", "/api/kis/price/005930", "/api/kis/trade-time/005930"]
    providers = {s["provider"] for s in out.sources}
    assert providers == {"KIS", "NAVER API HUB"}
    news = [s for s in out.sources if s["provider"] == "NAVER API HUB"][0]
    assert news["url"] == "https://news.example/1" and "<b>" not in news["label"]


def test_earnings_source_has_year_and_basis():
    client, _ = make_client()
    llm = FakeLlm()
    out = run(handle("삼성전자 최근 실적 알려줘", [], None, client, llm))
    src = out.sources[0]
    assert src["provider"] == "OpenDART" and "2025년" in src["basisAt"] and "연결" in src["basisAt"]
    assert "300조" in llm.messages[-1]["content"]


def test_all_tools_fail_does_not_call_llm():
    client, _ = make_client(fail=("/price/",))
    llm = FakeLlm()
    out = run(handle("삼성전자 현재가 알려줘", [], None, client, llm))
    assert out.status == "no_data" and llm.called == 0


def test_partial_failure_notes_missing():
    client, _ = make_client(fail=("/news/",))
    llm = FakeLlm()
    out = run(handle("삼성전자 왜 올랐어?", [], None, client, llm))
    assert out.status == "answered" and "확인하지 못한 자료" in out.answer
    assert "[누락]" in llm.messages[-1]["content"]


def test_need_more_info_when_no_company():
    client, calls = make_client()
    out = run(handle("현재가 알려줘", [], None, client, FakeLlm()))
    assert out.status == "need_more_info" and not calls


def test_selected_stock_used_as_default():
    client, calls = make_client()
    run(handle("현재가 알려줘", [], "000660", client, FakeLlm()))
    assert "/api/kis/price/000660" in calls


def test_unsupported_company():
    client, calls = make_client()
    out = run(handle("카카오 주가 알려줘", [], None, client, FakeLlm()))
    assert out.status == "unsupported" and not calls


def test_history_limited_to_10_and_roles_filtered():
    client, _ = make_client()
    llm = FakeLlm()
    history = [{"role": "user" if i % 2 == 0 else "assistant", "content": f"m{i}"} for i in range(14)]
    history.append({"role": "system", "content": "무시"})
    run(handle("부채비율이 뭐야?", history, None, client, llm))
    middle = llm.messages[1:-1]
    assert len(middle) <= 10 and all(m["role"] in ("user", "assistant") for m in middle)


def test_markdown_and_directives_removed():
    assert "**" not in strip_markdown("**굵게** `코드`\n# 제목\n- 항목")
    text = drop_directive_sentences("지금은 하락했어요. 지금 사세요. 확인이 필요해요.")
    assert "사세요" not in text and "하락" in text


def test_llm_failure_returns_error_status():
    from app.llm import LlmError

    async def boom(client, messages):
        raise LlmError("x")

    client, _ = make_client()
    out = run(handle("부채비율이 뭐야?", [], None, client, boom))
    assert out.status == "error"
