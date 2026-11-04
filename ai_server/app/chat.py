"""챗봇 처리: 분류 → Tool 조회 → (필요할 때만) OpenAI → 후처리."""
import asyncio
import re
from dataclasses import dataclass
from typing import Awaitable, Callable

import httpx

from . import tools
from .classifier import classify
from .config import settings
from .llm import LlmError
from .prompts import LLM_FAIL, NO_DATA, OUT_OF_SCOPE, SUPPORTED, SYSTEM_PROMPT

LlmFn = Callable[[httpx.AsyncClient, list[dict]], Awaitable[str]]

TOOL_FUNCS = {
    "quote": tools.get_stock_quote,
    "earnings": tools.get_financial_result,
    "news": tools.get_company_news,
}
TOOL_LABEL = {"quote": "현재가", "earnings": "실적", "news": "관련 기사"}

_ORDER_RE = re.compile(r"(사세요|파세요|매수하세요|매도하세요|사야 합니다|팔아야 합니다|반드시 (오|상승|하락)|무조건|확실히 (오|상승)|수익을 보장|손실은 없)")


@dataclass
class ChatOutcome:
    status: str
    answer: str
    sources: list[dict]


def strip_markdown(text: str) -> str:
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    text = re.sub(r"^\s{0,3}#{1,6}\s*", "", text, flags=re.M)
    text = re.sub(r"(\*\*|__|`)", "", text)
    text = re.sub(r"^\s*[-*•]\s+", "", text, flags=re.M)
    text = re.sub(r"^\s*\|.*\|\s*$", "", text, flags=re.M)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def drop_directive_sentences(text: str) -> str:
    """매수·매도 지시나 수익 보장으로 읽히는 문장은 빼고 남긴다."""
    sentences = re.split(r"(?<=[.!?。])\s+", text)
    kept = [s for s in sentences if not _ORDER_RE.search(s)]
    return " ".join(kept).strip() if kept else ""


def _history_messages(history: list[dict]) -> list[dict]:
    result = []
    for item in history[-settings.history_limit:]:
        role = item.get("role")
        content = str(item.get("content", ""))[:2000]
        if role in ("user", "assistant") and content:
            result.append({"role": role, "content": content})
    return result


async def handle(question: str, history: list[dict], selected_code: str | None,
                 client: httpx.AsyncClient, llm: LlmFn) -> ChatOutcome:
    question = question.strip()
    history = _history_messages(history)
    plan = classify(question, selected_code, bool(history))

    if plan.kind == "out_of_scope":
        return ChatOutcome("out_of_scope", OUT_OF_SCOPE, [])
    if plan.kind == "unsupported":
        return ChatOutcome("unsupported", SUPPORTED, [])
    if plan.kind == "need_info":
        return ChatOutcome("need_more_info", f"{plan.missing}를 알려 주시면 확인해 드릴게요.", [])

    facts_text, sources, missing = "", [], []
    if plan.kind == "data":
        jobs, labels = [], []
        for code in plan.codes:
            for key in plan.tools:
                jobs.append(TOOL_FUNCS[key](client, code))
                labels.append((code, key))
        results = await asyncio.gather(*jobs)
        ok = [r for r in results if r.ok]
        for (code, key), r in zip(labels, results):
            if not r.ok:
                missing.append(f"{tools.company_name(code)} {TOOL_LABEL[key]}")
        if not ok:
            return ChatOutcome("no_data", NO_DATA, [])
        facts_text = "\n".join(r.facts for r in ok)
        for r in ok:
            sources.extend(r.sources)

    user_content = question
    if plan.kind == "data":
        user_content += f"\n\n[조회 결과]\n{facts_text}"
        if missing:
            user_content += f"\n[누락] 다음 자료는 확인하지 못했다: {', '.join(missing)}"
    else:
        user_content += "\n\n[조회 결과] 없음. 일반 개념 설명이며 특정 기업의 현재 수치는 말하지 않는다."

    messages = [{"role": "system", "content": SYSTEM_PROMPT}, *history, {"role": "user", "content": user_content}]
    try:
        raw = await llm(client, messages)
    except LlmError:
        return ChatOutcome("error", LLM_FAIL, [])

    answer = drop_directive_sentences(strip_markdown(raw))
    if not answer:
        return ChatOutcome("error", LLM_FAIL, [])
    if missing:
        answer += f"\n\n확인하지 못한 자료: {', '.join(missing)}"
    return ChatOutcome("answered", answer, sources)
