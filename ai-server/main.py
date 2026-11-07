"""K-FinCheck AI 서버 (FastAPI :8087)

Spring 서버만 호출한다: POST /chat
  1. 질문 분류 (classifier) → 애매하면 OpenAI 로 해석 (interpreter) → 서버가 필요한 Tool 선택
  2. Tool 호출 (tools, 각 5초 제한) → Spring API 로 시세·실적·공시·기업 정보 조회
  3. OpenAI 에 조회 결과 + 답변 지침 + 최근 대화(최대 10개)를 주고 답변 문장만 생성
  4. {"status","answer","sources"} 반환 (sources 는 Tool 결과로 직접 만든다)

실행: uvicorn main:app --host 127.0.0.1 --port 8087
"""

import logging
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

import classifier
import config
import interpreter
import llm
import prompts
import tools

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [ai-server] %(message)s")
log = logging.getLogger("ai-server")


@asynccontextmanager
async def lifespan(_: FastAPI):
    log.info("Spring 서버: %s, Tool 제한 시간: %gs", config.SPRING_BASE_URL, config.TOOL_TIMEOUT_SECONDS)
    log.info(await llm.check_model())
    yield


app = FastAPI(title="K-FinCheck AI Server", version="2.0.0", lifespan=lifespan)


class HistoryItem(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    symbol: str = Field(pattern=r"^\d{6}$")    # 대화창을 연 종목 (질문에 기업명이 없을 때 기준)
    stockName: str = ""
    history: list[HistoryItem] = Field(default_factory=list)


class Source(BaseModel):
    provider: str
    label: str
    basisAt: str | None = None
    url: str | None = None


class ChatResponse(BaseModel):
    status: Literal["answered", "need_more_info", "out_of_scope", "unavailable", "error"]
    answer: str
    sources: list[Source] = Field(default_factory=list)
    visuals: list[dict] = Field(default_factory=list)  # 화면의 카드·표·그래프·링크용 조회 자료


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model": config.OPENAI_MODEL,
        "openaiConfigured": llm.is_configured(),
        "springBaseUrl": config.SPRING_BASE_URL,
        "toolTimeoutSeconds": config.TOOL_TIMEOUT_SECONDS,
    }


@app.post("/chat", response_model=ChatResponse)
async def chat(body: ChatRequest, x_internal_key: str | None = Header(default=None)):
    if config.AI_INTERNAL_KEY and x_internal_key != config.AI_INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="허용되지 않은 요청입니다.")
    return await answer(body)


async def answer(body: ChatRequest) -> ChatResponse:
    question = body.question.strip()
    history = [h.model_dump() for h in body.history][-config.MAX_HISTORY:]

    try:
        companies = await tools.list_companies()
    except tools.ToolError:
        companies = []
    current = next((c for c in companies if c.symbol == body.symbol), None) \
        or classifier.Company(body.symbol, body.stockName or body.symbol)
    if current not in companies:
        companies = companies + [current]

    plan = classifier.classify(question, current, companies, history)
    plan = await interpreter.refine(question, plan, current, companies, history)
    log.info("질문 분류: kind=%s type=%s tools=%s", plan.kind, plan.question_type,
             [f"{c.name}:{c.symbol}" for c in plan.calls])

    # 금융과 무관한 질문 · 조건이 부족한 질문은 LLM 에 보내지 않고 서버 안내 문구
    if plan.kind == classifier.OUT_OF_SCOPE:
        return ChatResponse(status="out_of_scope", answer=plan.message)
    if plan.kind == classifier.NEED_MORE_INFO:
        return ChatResponse(status="need_more_info", answer=plan.message)
    if plan.kind == classifier.NO_DATA:
        return ChatResponse(status="unavailable", answer=plan.message)

    results = await tools.run_tools(plan.calls)
    for r in results:
        if not r.ok:
            log.warning("Tool 실패: %s %s - %s", r.name, r.symbol, r.error)

    # 모든 Tool 이 실패하면 LLM 지식으로 대신 답하지 않는다
    if plan.calls and not any(r.ok for r in results):
        # 자료가 없는 것과 일시적인 장애는 안내를 구분한다
        if all(r.no_data for r in results):
            return ChatResponse(status="unavailable", answer=prompts.no_data_message(results))
        return ChatResponse(status="unavailable", answer=prompts.UNAVAILABLE_MESSAGE)

    messages = prompts.build_input(question, plan, results, companies, history)
    try:
        text = await llm.generate(prompts.SYSTEM_PROMPT, messages)
        # 조회 자료에 없는 비율·금액이 있으면 한 번 다시 만들고, 그래도 있으면 표시하지 않는다
        unknown = prompts.unverified_numbers(text, plan, results, companies) if plan.calls else []
        if unknown:
            log.warning("조회 자료에 없는 수치: %s", unknown)
            retry = messages + [{"role": "assistant", "content": text},
                                {"role": "user", "content": f"방금 답변의 {', '.join(unknown)}은(는) 조회 자료에 없습니다. "
                                                            "조회 자료에 적힌 값만 써서 다시 답하세요."}]
            text = await llm.generate(prompts.SYSTEM_PROMPT, retry)
            if prompts.unverified_numbers(text, plan, results, companies):
                return ChatResponse(status="unavailable", answer=prompts.UNVERIFIED_MESSAGE)
    except llm.LlmError as e:
        return ChatResponse(status="error", answer=str(e))

    return ChatResponse(
        status="answered",
        answer=prompts.strip_markdown(text),
        sources=[Source(**s) for s in prompts.build_sources(results, companies)],
        visuals=prompts.build_visuals(results, companies),
    )
