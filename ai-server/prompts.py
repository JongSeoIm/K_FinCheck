"""답변 지침 (PDF "답변 지침", "답변 지침 추가 규칙", "답변 지침 세부 사항") + Tool 결과 정리 + sources 생성"""

import json
import re
from datetime import datetime

from classifier import (COMPANY, COMPARE, DISCLOSURE_TOOL, FINANCIAL, FINANCIAL_TOOL, INFO_TOOL, KST, MOVEMENT, NEWS,
                        OUTLOOK, QUOTE, QUOTE_TOOL, TERM, Company, Plan)
from tools import ToolResult

SYSTEM_PROMPT = """당신은 국내 주식 정보 서비스 'K-FinCheck'의 금융 도우미입니다.
사용자는 대학생 또는 주식 초보자입니다.

[답변 지침]
- 사용자가 물어본 내용부터 직접 답하고 불필요한 내용을 덧붙이지 않는다.
- 대학생 또는 주식 초보자가 이해할 수 있는 쉬운 표현을 사용한다.
- Markdown 기호(#, *, -, **, 표, 코드 블록)를 쓰지 않고 일반 문장으로 답한다. 문단 구분은 빈 줄로만 한다.
- 개념 질문은 정의, 계산 방식, 짧은 예시 순서로 설명한다.
- 현재가·실적·공시 질문은 반드시 [조회 자료]에 있는 값만 사용한다.
- 수치에는 단위와 기준 시점을 함께 쓴다.
- 조회하지 못한 값은 추측하거나 임의로 만들지 않는다. [조회 실패] 항목이 있으면 그 자료는 지금 확인할 수 없다고 짧게 알린다.
- 금액과 비율은 이번 [조회 자료]에 적힌 값만 쓴다. 이전 대화나 알고 있는 지식의 수치를 쓰지 않고, 직접 계산한 차이·비율도 쓰지 않는다.
- 주가 변동 원인은 확인된 사실과 가능한 해석을 구분해서 말한다. 해석은 "~일 수 있어요"처럼 단정하지 않는다.
- 매수·매도를 직접 지시하거나 수익을 보장하지 않는다.
- 출처 이름과 링크는 화면에 따로 표시되므로 본문에 URL을 쓰지 않는다.

[추가 규칙]
- Tool 조회에 실패한 수치는 LLM 지식으로 대신 답하지 않는다.
- 조회되지 않은 공시나 실적을 만들어내지 않는다.
- 주가 상승·하락 원인을 단정하지 않는다.
- 현재가를 말할 때는 체결 시각을 함께 말한다. [조회 자료]에 체결시각이 없고 조회시각만 있으면 "체결 시각은 확인되지 않았고 조회 시각은 ~"처럼 구분해서 말한다.
- 실적을 말할 때는 사업연도, 보고서 종류, 연결·별도 기준을 함께 말한다.
- 체결 시각은 현재가를 말할 때만 쓴다. 실적·공시 답변에서는 체결 시각을 언급하지 않는다.
- 영업이익률·부채비율·유동비율·매출 증감률은 [조회 자료]의 재무지표 값을 쓴다. 값 대신 상태가 적혀 있으면 그 상태를 그대로 말하고 0%로 말하지 않는다.
- 분기·반기 보고서의 손익 수치는 [조회 자료]의 당기기간에 적힌 3개월 값이다. 상반기 전체나 1년 값이라고 말하지 않는다.
- 뉴스 기사는 조회하지 않는다. 기사 제목이나 내용을 말하거나 지어내지 않고, 뉴스를 물으면 기사는 화면의 관련 뉴스 패널에서 볼 수 있다고 안내한다.
- 공시를 말할 때는 공시 이름과 접수일을 함께 말한다. 공시 이름에 없는 세부 내용을 덧붙이지 않는다.
- 질문이 금융과 무관하면 "주식·금융 질문만 도와드릴 수 있어요"라고 한 문장으로 안내한다.
- 조회 대상이 아닌 다른 기업을 물으면 지원 종목만 조회할 수 있다고 안내한다.
"""

# 질문 종류별 답변 분량 (PDF "답변 지침 세부 사항")
LENGTH_GUIDE = {
    TERM: "금융 용어 설명: 2~4문장. 정의, 계산 방식, 간단한 예시 순서.",
    QUOTE: "현재가·등락 질문: 2~5문장. 현재가, 전일 대비, 등락률과 체결 시각을 표시.",
    FINANCIAL: "기업 실적 질문: 3~6문장. 매출액·영업이익·당기순이익 수치, 전년 대비 변화, 사업연도·보고서 종류·연결/별도 기준을 표시.",
    NEWS: "뉴스·공시 질문: 2~5문장. 뉴스를 물었으면 기사는 화면의 관련 뉴스 패널에서 볼 수 있다고 먼저 안내하고, 조회한 최근 공시의 이름과 접수일을 알려준다.",
    MOVEMENT: "주가 변동 원인: 4~8문장. 실제 등락을 먼저 말하고, 조회한 공시·실적만으로 설명한다. 사실과 해석을 구분하고, 자료로 원인을 확인할 수 없으면 그렇다고 말한다. 뉴스는 화면의 패널에서 볼 수 있다고 안내한다. "
              "조회한 등락은 전일 대비 값이므로 더 긴 기간을 물으면 그 기간의 등락은 확인할 수 없다고 밝힌다.",
    OUTLOOK: "전망·매수 여부: 단정하지 않는다. 확인된 자료를 정리하고 판단할 때 주의할 점을 설명한다. 매수·매도를 권하지 않는다.",
    COMPANY: "기업 정보 질문: 2~4문장. 기업명, 종목 코드, 시장 구분을 알려준다.",
    COMPARE: "기업 비교: 3~6문장. 같은 기준(시점·연결/별도)의 수치끼리 비교하고 기준이 다르면 그 점을 밝힌다.",
}
NO_DATA_GUIDE = "자료가 없는 질문: 1~3문장. 부족한 자료와 확인 가능한 범위를 안내한다."

OUT_OF_SCOPE_STATUS = "out_of_scope"
UNAVAILABLE_MESSAGE = "지금은 필요한 자료를 확인할 수 없어요. 잠시 후 다시 질문해 주세요."
NO_DATA_GUIDE_MESSAGE = "최근 실적, 현재가, 공시를 물어봐 주세요. 뉴스는 화면의 관련 뉴스 패널에서 볼 수 있어요."
UNVERIFIED_MESSAGE = "조회 자료로 확인되지 않는 수치가 답변에 섞여 있어 표시하지 않았어요. 질문을 조금 바꿔서 다시 물어봐 주세요."

DISCLOSURE_PROVIDER = "OpenDART 공시"

TOOL_LABEL = {
    QUOTE_TOOL: "현재가",
    FINANCIAL_TOOL: "실적",
    DISCLOSURE_TOOL: "공시",
    INFO_TOOL: "기업 정보",
}


# ===== 숫자 표시 =====
def format_krw(amount: int | float | None) -> str | None:
    """1234567890000 → '1조 2,345억 원' (억 단위 반올림)"""
    if amount is None:
        return None
    sign = "-" if amount < 0 else ""
    value = abs(int(amount))
    if value >= 100_000_000:
        eok = round(value / 100_000_000)
        jo, rest = divmod(eok, 10_000)
        if jo and rest:
            return f"{sign}{jo:,}조 {rest:,}억 원"
        if jo:
            return f"{sign}{jo:,}조 원"
        return f"{sign}{rest:,}억 원"
    return f"{sign}{value:,}원"


def change_rate(current: int | None, previous: int | None) -> float | None:
    if current is None or previous in (None, 0):
        return None
    return round((current - previous) / abs(previous) * 100, 1)


def _name(symbol: str, companies: list[Company]) -> str:
    return next((c.name for c in companies if c.symbol == symbol), symbol)


# 보고서별 손익 금액의 기간 (OpenDART 주요계정의 당기·전기 금액 기준)
_QUARTER_LABEL = {"11013": "1분기(1~3월) 3개월", "11012": "2분기(4~6월) 3개월", "11014": "3분기(7~9월) 3개월"}


def _income_periods(year: str | None, report: str | None) -> tuple[str | None, str | None]:
    """(당기 기간, 전기 기간) 예: ('2026년 2분기(4~6월) 3개월', '2025년 2분기(4~6월) 3개월')"""
    if not year or not year.isdigit():
        return None, None
    previous = int(year) - 1
    if report in _QUARTER_LABEL:
        return f"{year}년 {_QUARTER_LABEL[report]}", f"{previous}년 {_QUARTER_LABEL[report]}"
    return f"{year}년 1년", f"{previous}년 1년"


# ===== Tool 결과 → LLM 에 넘길 자료 =====
def asked_year(plan: Plan) -> str | None:
    """질문이 지정한 사업연도 ("작년", "2024년"). 없으면 None (최근 공시)"""
    return next((c.year for c in plan.calls if c.name == FINANCIAL_TOOL and c.year), None)


_METRIC_STATUS = {"NOT_CALCULABLE": "계산 불가", "MISSING": "자료 없음"}


def _metric(metric: dict) -> dict:
    """재무 지표는 Spring 이 계산한 값을 그대로 넘긴다"""
    value = metric.get("value")
    if metric.get("status") == "OK" and value is not None:
        return {"지표": metric.get("name"), "값": f"{value:.2f}%"}
    return {"지표": metric.get("name"), "상태": _METRIC_STATUS.get(metric.get("status"), "자료 없음")}


def _evidence(result: ToolResult, name: str, hide_previous: bool = False) -> dict:
    """hide_previous: 사업연도를 지정한 질문은 전기 금액을 넘기지 않는다 ("작년"에 그 전해 금액으로 답하는 것을 막는다)"""
    data = result.data
    if result.name == QUOTE_TOOL:
        event_time = data.get("eventTime")
        return {
            "종류": "현재가", "기업": name, "제공처": data.get("source"),
            "모의데이터여부": data.get("source") == "DEMO",
            "현재가": f"{data.get('price'):,}원" if data.get("price") is not None else None,
            "전일대비": f"{data.get('changeAmount'):+,}원" if data.get("changeAmount") is not None else None,
            "등락률": f"{data.get('changeRate'):+.2f}%" if data.get("changeRate") is not None else None,
            "시가": data.get("open"), "고가": data.get("high"), "저가": data.get("low"),
            "전일종가": data.get("previousClose"), "누적거래량": data.get("cumulativeVolume"),
            "체결시각": event_time,
            "조회시각": None if event_time else data.get("receivedAt"),
        }
    if result.name == FINANCIAL_TOOL:
        current_period, previous_period = _income_periods(data.get("bsnsYear"), data.get("reprtCode"))
        items = []
        for item in data.get("items", []):
            if item.get("statement") == "ETC":
                continue
            items.append({
                "계정": item.get("accountName"),
                "구분": "손익" if item.get("statement") == "IS" else "재무상태",
                "당기": format_krw(item.get("current")),
                "전기": None if hide_previous else format_krw(item.get("previous")),
                "증감률": (f"{rate:+.1f}%" if (rate := change_rate(item.get("current"), item.get("previous"))) is not None else None),
            })
        return {
            "종류": "실적", "기업": name, "제공처": "OpenDART",
            "사업연도": data.get("bsnsYear"), "보고서": data.get("reportName"), "기준": data.get("fsName"),
            "당기기간": current_period, "전기기간": previous_period,
            "손익항목기준": data.get("incomeBasis"), "재무상태항목기준": data.get("balanceBasis"),
            "항목": items,
            "재무지표": [_metric(m) for m in data.get("metrics") or []],
        }
    if result.name == DISCLOSURE_TOOL:
        return {
            "종류": "최근 공시", "기업": name, "제공처": "OpenDART",
            "공시": [{"공시이름": d.get("reportName"), "접수일": d.get("filedAt"), "제출인": d.get("submitter")} for d in data],
        }
    if result.name == INFO_TOOL:
        return {"종류": "기업 정보", "기업명": data.get("name"), "종목코드": data.get("symbol"), "시장": data.get("market")}
    return {"종류": result.name, "자료": data}


def build_input(question: str, plan: Plan, results: list[ToolResult], companies: list[Company],
                history: list[dict]) -> list[dict]:
    """Responses API input: 최근 대화(최대 10개) + 이번 질문과 조회 자료"""
    now = datetime.now(KST)
    targets = ", ".join(f"{c.name}({c.symbol})" for c in plan.targets) or "없음"
    supported = ", ".join(c.name for c in companies)

    lines = [
        f"[지금 시각] {now.strftime('%Y-%m-%d %H:%M')} (한국 시간)",
        f"[지원 종목] {supported}",
        f"[조회 대상] {targets}",
        f"[답변 분량] {LENGTH_GUIDE.get(plan.question_type, NO_DATA_GUIDE)}",
    ]
    # "작년"이 몇 년인지 LLM 이 앞 대화로 짐작하지 않도록 서버가 해석한 사업연도를 알려준다
    year = asked_year(plan)
    if year:
        lines.append(f"[기간 기준] 올해는 {now.year}년, 작년은 {now.year - 1}년이다. 이번 질문이 묻는 사업연도는 {year}년이다. "
                     f"답의 수치는 {year}년 자료의 당기 값을 쓰고, 증감률은 {int(year) - 1}년 대비 값이다.")
    ok = [r for r in results if r.ok]
    failed = [r for r in results if not r.ok]
    if ok:
        evidence = [_evidence(r, _name(r.symbol, companies), hide_previous=bool(year)) for r in ok]
        lines.append("[조회 자료]\n" + json.dumps(evidence, ensure_ascii=False, indent=1))
    elif plan.calls:
        lines.append("[조회 자료] 없음")
    else:
        lines.append("[조회 자료] 이 질문은 외부 자료 없이 개념을 설명하는 질문이다. 특정 기업의 현재 수치는 말하지 않는다.")
    if failed:
        lines.append("[조회 실패] " + ", ".join(
            f"{_name(r.symbol, companies)} {TOOL_LABEL.get(r.name, r.name)}({r.error})" for r in failed)
            + " → 이 자료는 추측하지 말고 확인할 수 없다고 안내")
    if year:
        # 앞 대화의 보고서 연도를 기준으로 "작년"을 해석하지 않도록 실제 연도로 바꿔서 전달한다
        for word, value in (("재작년", now.year - 2), ("작년", now.year - 1), ("지난해", now.year - 1), ("올해", now.year)):
            question = question.replace(word, f"{value}년")
    lines.append(f"[질문] {question}")

    messages = [{"role": h["role"], "content": h["content"]} for h in history if h.get("role") in ("user", "assistant")]
    messages.append({"role": "user", "content": "\n".join(lines)})
    return messages


# ===== sources (PDF "sources 응답 형식") - LLM 이 아니라 Tool 결과로 직접 만든다 =====
def build_sources(results: list[ToolResult], companies: list[Company]) -> list[dict]:
    sources: list[dict] = []
    for r in results:
        if not r.ok:
            continue
        name = _name(r.symbol, companies)
        if r.name == QUOTE_TOOL:
            demo = r.data.get("source") == "DEMO"
            event_time = r.data.get("eventTime")
            sources.append({
                "provider": r.data.get("source") or "KIS",
                # 체결 시각을 확인하지 못하면 조회 시각임을 라벨에 밝힌다
                "label": f"{name} 현재가" + (" (모의 데이터)" if demo else "") + ("" if event_time else " (조회 시각 기준)"),
                "basisAt": event_time or r.data.get("receivedAt"),
                "url": None,
            })
        elif r.name == FINANCIAL_TOOL:
            d = r.data
            sources.append({
                "provider": "OpenDART",
                "label": f"{name} {d.get('bsnsYear')}년 {d.get('reportName')} ({d.get('fsName')})",
                "basisAt": f"{d.get('bsnsYear')} 사업연도",
                "url": d.get("url"),
            })
        elif r.name == DISCLOSURE_TOOL:
            # LLM 에 넘긴 공시는 모두 출처에 표시한다 (접수일·원문 링크)
            for d in r.data:
                sources.append({
                    "provider": DISCLOSURE_PROVIDER,
                    "label": f"{name} {d.get('reportName')}",
                    "basisAt": d.get("filedAt"),
                    "url": d.get("url"),
                })
        # get_company_info 는 우리 DB 기본 정보라 외부 출처를 표시하지 않는다
    return sources


# ===== visuals: 화면의 카드·표·그래프·링크 - sources 처럼 LLM 이 아니라 Tool 결과로 직접 만든다 =====
_EARNING_ACCOUNTS = ["REVENUE", "OPERATING_INCOME", "NET_INCOME"]


def _earning_compare(current: int | None, previous: int | None) -> tuple[float | None, str | None]:
    """(전년 대비 증감률, 증감률 대신 보여줄 문구)"""
    if current is None or previous is None or previous == 0:
        return None, "비교 불가"
    if previous < 0:
        return None, "흑자 전환" if current >= 0 else "적자 지속"
    if current < 0:
        return None, "적자 전환"
    return round((current - previous) / previous * 100, 1), None


def build_visuals(results: list[ToolResult], companies: list[Company]) -> list[dict]:
    visuals: list[dict] = []
    for r in results:
        if not r.ok:
            continue
        name = _name(r.symbol, companies)
        d = r.data
        if r.name == QUOTE_TOOL:
            visuals.append({
                "type": "quote", "name": name, "symbol": r.symbol, "source": d.get("source"),
                "price": d.get("price"), "changeAmount": d.get("changeAmount"), "changeRate": d.get("changeRate"),
                "open": d.get("open"), "high": d.get("high"), "low": d.get("low"), "volume": d.get("cumulativeVolume"),
                "eventTime": d.get("eventTime"), "receivedAt": d.get("receivedAt"),
            })
        elif r.name == FINANCIAL_TOOL:
            items = {item.get("accountId"): item for item in d.get("items", [])}
            rows = []
            for code in _EARNING_ACCOUNTS:
                item = items.get(code)
                if not item:
                    continue
                rate, label = _earning_compare(item.get("current"), item.get("previous"))
                rows.append({"code": code, "name": item.get("accountName"), "current": item.get("current"),
                             "previous": item.get("previous"), "changeRate": rate, "compareLabel": label})
            current_period, previous_period = _income_periods(d.get("bsnsYear"), d.get("reprtCode"))
            visuals.append({
                "type": "financial", "name": name, "symbol": r.symbol, "source": "OpenDART",
                "year": d.get("bsnsYear"), "report": d.get("reportName"), "fs": d.get("fsName"), "url": d.get("url"),
                "period": current_period, "previousPeriod": previous_period, "rows": rows,
            })
        elif r.name == DISCLOSURE_TOOL:
            visuals.append({
                "type": "disclosure", "name": name, "symbol": r.symbol, "source": "OpenDART",
                "items": [{"title": n.get("reportName"), "filedAt": n.get("filedAt"), "url": n.get("url")} for n in d],
            })
        elif r.name == INFO_TOOL:
            visuals.append({"type": "company", "name": d.get("name"), "symbol": d.get("symbol"), "market": d.get("market")})
    return visuals


# ===== 답변 수치 검사 (PDF "조회하지 못한 값은 추측하거나 임의로 생성하지 않는다") =====
_PERCENT = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*%")
_MONEY = re.compile(r"\d[\d,]*(?:\.\d+)?\s*조(?:\s*\d[\d,]*\s*억)?|\d[\d,]*\s*억")


def _compact(text: str) -> str:
    return re.sub(r"[,\s]", "", text)


def unverified_numbers(answer: str, plan: Plan, results: list[ToolResult], companies: list[Company]) -> list[str]:
    """답변의 비율·금액(조·억) 중 LLM 에 넘긴 조회 자료에 없는 값. 반올림해서 쓴 값은 허용한다."""
    hide_previous = bool(asked_year(plan))
    evidence = json.dumps([_evidence(r, _name(r.symbol, companies), hide_previous) for r in results if r.ok],
                          ensure_ascii=False)
    known_percents = [_compact(m) for m in _PERCENT.findall(evidence)]
    known_money = [_compact(m) for m in _MONEY.findall(evidence)]

    unknown = []
    for value in _PERCENT.findall(answer):
        text = _compact(value)
        digits = len(text.split(".")[1]) if "." in text else 0
        if not any(round(float(known), digits) == float(text) for known in known_percents):
            unknown.append(f"{value}%")
    for value in _MONEY.findall(answer):
        text = _compact(value)
        if not any(known == text or known.startswith(text) for known in known_money):
            unknown.append(value)
    return unknown


def no_data_message(results: list[ToolResult]) -> str:
    """자료가 없을 때: 부족한 자료와 확인 가능한 범위 (PDF "자료가 없는 질문")"""
    return " ".join(dict.fromkeys(r.error for r in results)) + " " + NO_DATA_GUIDE_MESSAGE


def strip_markdown(text: str) -> str:
    """지침을 어기고 Markdown 이 섞여 와도 일반 문장으로 보이게 정리"""
    cleaned = []
    for line in text.replace("\r\n", "\n").split("\n"):
        line = re.sub(r"^\s{0,3}#{1,6}\s*", "", line)          # 제목
        line = re.sub(r"^\s*[-*•]\s+", "", line)               # 목록 기호
        line = line.replace("**", "").replace("__", "").replace("`", "")
        cleaned.append(line.rstrip())
    return re.sub(r"\n{3,}", "\n\n", "\n".join(cleaned)).strip()
