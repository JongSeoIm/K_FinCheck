"""OpenAI 호출. 키는 환경변수에서만 읽고 로그에 남기지 않습니다."""
import httpx

from .config import settings


class LlmError(Exception):
    pass


async def complete(client: httpx.AsyncClient, messages: list[dict]) -> str:
    if not settings.openai_api_key:
        raise LlmError("OPENAI_API_KEY 가 설정되지 않았습니다.")
    try:
        response = await client.post(
            f"{settings.openai_base_url}/chat/completions",
            headers={"Authorization": f"Bearer {settings.openai_api_key}"},
            json={
                "model": settings.openai_model,
                "messages": messages,
                "max_completion_tokens": 900,
            },
            timeout=settings.llm_timeout,
        )
    except httpx.HTTPError as exc:
        raise LlmError(f"OpenAI 연결 실패: {type(exc).__name__}") from exc
    if response.status_code != 200:
        # 응답 본문에는 키 정보가 없지만, 그대로 노출하지 않고 요약만 남깁니다.
        detail = ""
        try:
            detail = response.json().get("error", {}).get("message", "")[:200]
        except ValueError:
            pass
        raise LlmError(f"OpenAI 응답 {response.status_code}: {detail}")
    try:
        text = response.json()["choices"][0]["message"]["content"]
    except (KeyError, IndexError, ValueError) as exc:
        raise LlmError("OpenAI 응답 형식 오류") from exc
    if not text or not text.strip():
        raise LlmError("빈 답변")
    return text


async def check_model(client: httpx.AsyncClient) -> dict:
    """키와 모델 사용 가능 여부 확인 (모델 목록 조회)."""
    if not settings.openai_api_key:
        return {"ok": False, "reason": "OPENAI_API_KEY 가 설정되지 않았습니다."}
    try:
        response = await client.get(
            f"{settings.openai_base_url}/models",
            headers={"Authorization": f"Bearer {settings.openai_api_key}"},
            timeout=15,
        )
    except httpx.HTTPError as exc:
        return {"ok": False, "reason": f"연결 실패: {type(exc).__name__}"}
    if response.status_code != 200:
        return {"ok": False, "reason": f"OpenAI 응답 {response.status_code}"}
    ids = sorted(item.get("id", "") for item in response.json().get("data", []))
    return {
        "ok": settings.openai_model in ids,
        "model": settings.openai_model,
        "reason": "" if settings.openai_model in ids else "설정한 모델이 이 키로 사용 가능한 목록에 없습니다.",
        "gptModels": [i for i in ids if i.startswith("gpt-5")][:30],
    }
