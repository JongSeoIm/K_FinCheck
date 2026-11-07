"""OpenAI 호출 (최종 답변 문장 생성, 질문 해석 보조. Tool 선택·외부 API 호출은 하지 않는다)"""

import logging

from openai import AsyncOpenAI

import config

log = logging.getLogger("ai-server")

_client: AsyncOpenAI | None = None


class LlmError(Exception):
    pass


def is_configured() -> bool:
    return bool(config.OPENAI_API_KEY)


def _get_client() -> AsyncOpenAI:
    global _client
    if _client is None:
        _client = AsyncOpenAI(api_key=config.OPENAI_API_KEY, timeout=config.OPENAI_TIMEOUT_SECONDS, max_retries=1)
    return _client


async def check_model() -> str:
    """서버 시작 시 사용할 모델이 이 키로 사용 가능한지 확인 (PDF: 사용 가능 모델 확인)"""
    if not is_configured():
        return "OPENAI_API_KEY 가 없어 AI 답변을 만들 수 없습니다. (backend/.env 또는 ai-server/.env)"
    try:
        await _get_client().models.retrieve(config.OPENAI_MODEL)
        return f"OpenAI 모델 사용 가능: {config.OPENAI_MODEL}"
    except Exception as e:  # 키 오류·모델 없음·네트워크
        return f"OpenAI 모델 확인 실패 ({config.OPENAI_MODEL}): {type(e).__name__} {e}"


async def generate(instructions: str, messages: list[dict]) -> str:
    if not is_configured():
        raise LlmError("AI 서버에 OPENAI_API_KEY 가 설정되지 않았습니다.")
    params = {
        "model": config.OPENAI_MODEL,
        "instructions": instructions,
        "input": messages,
        "max_output_tokens": config.OPENAI_MAX_OUTPUT_TOKENS,
    }
    if config.OPENAI_REASONING_EFFORT:
        params["reasoning"] = {"effort": config.OPENAI_REASONING_EFFORT}
    try:
        response = await _get_client().responses.create(**params)
    except Exception as e:
        log.warning("OpenAI 호출 실패: %s %s", type(e).__name__, e)
        raise LlmError("AI 답변을 만들지 못했습니다.") from e
    text = (response.output_text or "").strip()
    if not text:
        raise LlmError("AI 답변이 비어 있습니다.")
    return text


async def parse(instructions: str, messages: list[dict], schema: type):
    """정해진 형식(schema)으로 응답을 받는다. 실패하면 LlmError"""
    if not is_configured():
        raise LlmError("AI 서버에 OPENAI_API_KEY 가 설정되지 않았습니다.")
    params = {
        "model": config.OPENAI_MODEL,
        "instructions": instructions,
        "input": messages,
        "text_format": schema,
        "max_output_tokens": config.OPENAI_INTERPRET_MAX_TOKENS,
    }
    if config.OPENAI_REASONING_EFFORT:
        params["reasoning"] = {"effort": config.OPENAI_REASONING_EFFORT}
    try:
        response = await _get_client().with_options(timeout=config.OPENAI_INTERPRET_TIMEOUT_SECONDS).responses.parse(**params)
    except Exception as e:
        log.warning("질문 해석 실패: %s %s", type(e).__name__, e)
        raise LlmError("질문을 해석하지 못했습니다.") from e
    if response.output_parsed is None:
        log.warning("질문 해석 응답을 읽지 못했습니다.")
        raise LlmError("질문을 해석하지 못했습니다.")
    return response.output_parsed
