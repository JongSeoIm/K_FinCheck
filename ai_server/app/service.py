"""질문 처리 순서: 입력 검사 → 질문 분류 → Tool 조회 → LLM 답변 → 답변 검사.

대화 기록은 Spring 이 DB 에 보관합니다. 이 서버는 요청마다 최근 메시지와 직전 질문 정보를 받아 쓰고 저장하지 않습니다.
"""

import logging

from . import guard
from .composite import facts_of, missing_text, run_tools
from .provider import ChatProvider, LlmError
from .question_context import CompanyDirectory, compact
from .question_interpreter import LENGTHS, Interpretation, apply_llm_reading, finish, interpret
from .schemas import ChatRequest, ChatResponse, ConversationContext, Source
from .spring_client import SpringClient, SpringError

log = logging.getLogger("ai_server")
NO_DATA = "현재 자료를 확인할 수 없습니다."
FAILED = "답변을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요."
RETRY_GUIDE = "앞선 답변에 조회 결과에 없는 수치나 수익 보장 표현이 있었습니다. 조회 결과의 수치만 그대로 쓰고 수익을 보장하지 마세요."


class ChatService:
    def __init__(self, spring: SpringClient, provider: ChatProvider):
        self.spring = spring
        self.provider = provider
        self.directory = CompanyDirectory(spring)

    async def ask(self, request: ChatRequest) -> ChatResponse:
        context = request.context or ConversationContext()
        if guard.is_blocked(request.message):
            return ChatResponse(status="out_of_scope", answer=guard.BLOCKED_REPLY, context=context)

        try:
            companies = await self.directory.load()
        except SpringError as error:
            return ChatResponse(status="no_data", answer=f"{NO_DATA} {error}", context=context)

        plan = interpret(request.message, request.stock.name, companies, context)
        if plan.ask_llm:
            plan = await self._read_with_llm(request, companies, plan)
        if plan.reply:
            return ChatResponse(status=plan.reply_status, answer=plan.reply, context=context)
        return await self._answer(request, context, plan)

    async def _read_with_llm(self, request: ChatRequest, companies: list[dict], plan: Interpretation) -> Interpretation:
        """규칙이 애매한 질문만 LLM 해석을 거칩니다. 실패하면 규칙의 안내 문구로 답합니다."""
        found = await self._listed_company(plan.unknown) if plan.unknown and plan.wanted else None
        if found:
            return finish(plan.wanted, [found["companyName"]], compact(request.message), plan.year, request.stock.name)

        recent = [m.content for m in request.history if m.role == "user"][-3:]
        try:
            reading = await self.provider.read_question(request.message, recent, request.stock.name, [c["companyName"] for c in companies])
        except LlmError as error:
            log.warning("질문 해석 실패: %s", error.__cause__ or error)
            return plan
        if reading is None:
            return plan
        known = {c["companyName"] for c in companies} | {"selected", "unknown"}
        if reading.company not in known:
            listed = await self._listed_company(reading.company)
            companies = [*companies, listed] if listed else companies
        return apply_llm_reading(reading, request.message, request.stock.name, companies, plan)

    async def _listed_company(self, name: str) -> dict | None:
        """지원 종목 밖의 국내 상장사를 정확한 이름으로 찾습니다."""
        try:
            matches = await self.spring.companies(name)
        except SpringError:
            return None
        return next((m for m in matches if compact(m["companyName"]) == compact(name)), None)

    async def _answer(self, request: ChatRequest, context: ConversationContext, plan: Interpretation) -> ChatResponse:
        results = await run_tools(self.spring, plan.tools, plan.companies, plan.year) if plan.tools else []
        succeeded = [r for r in results if r.ok]
        if results and not succeeded:
            return ChatResponse(status="no_data", answer=f"{NO_DATA} {missing_text(results)}", context=context)

        facts = "\n\n".join(facts_of(r) for r in succeeded) or None
        missing = missing_text(results)
        if facts and missing:
            facts += f"\n\n확인하지 못한 자료: {missing}"

        try:
            answer = await self.provider.generate(LENGTHS[plan.category], request.history, request.message, facts)
        except LlmError as error:
            log.warning("OpenAI 호출 실패: %s", error.__cause__ or error)
            return ChatResponse(status="failed", answer=FAILED, context=context)

        answer = await self._retry_if_invalid(request, plan, answer, facts)
        next_context = ConversationContext(
            category=plan.category,
            company=plan.companies[0] if plan.companies else context.company,
            year=plan.year,
        )
        return ChatResponse(status="answered", answer=self._checked(answer, facts), sources=self._sources(succeeded), context=next_context)

    @staticmethod
    def _valid(answer: str, facts: str | None) -> bool:
        cleaned = guard.clean_answer(answer)
        return not facts or (not guard.has_guarantee(cleaned) and guard.numbers_supported(cleaned, facts))

    async def _retry_if_invalid(self, request: ChatRequest, plan: Interpretation, answer: str, facts: str | None) -> str:
        """조회 자료에 없는 수치나 수익 보장 표현이 있으면 한 번만 다시 만듭니다."""
        if self._valid(answer, facts):
            return answer
        log.warning("답변 검사 실패, 다시 생성")
        guide = f"{LENGTHS[plan.category]} {RETRY_GUIDE}"
        try:
            return await self.provider.generate(guide, request.history, request.message, facts)
        except LlmError as error:
            log.warning("다시 생성 실패: %s", error.__cause__ or error)
            return facts

    @classmethod
    def _checked(cls, answer: str, facts: str | None) -> str:
        if cls._valid(answer, facts):
            return guard.clean_answer(answer)
        log.warning("답변 검사 실패, 조회 결과로 대체")
        return guard.clean_answer(facts)

    @staticmethod
    def _sources(results) -> list[Source]:
        seen: set[tuple] = set()
        sources: list[Source] = []
        for result in results:
            for source in result.sources:
                key = (source.provider, source.label, source.url)
                if key not in seen:
                    seen.add(key)
                    sources.append(source)
        return sources
