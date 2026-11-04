from openai import AsyncOpenAI, OpenAIError

INSTRUCTIONS = """당신은 국내 주식 서비스 K-FinCheck 의 금융 안내 도우미입니다.
대학생이나 주식 초보자가 이해할 수 있는 쉬운 한국어로 답합니다.
물어본 내용부터 바로 답하고, Markdown 기호와 표 없이 일반 문장으로 답합니다.
현재가, 등락률, 실적, 기사처럼 최신 자료가 필요한 내용은 제공받은 자료가 없으므로 수치나 사실을 말하지 말고, 지금은 확인할 수 없다고 답합니다.
매수나 매도를 지시하지 않고 수익을 보장하지 않습니다."""


class LlmError(Exception):
    pass


class OpenAiProvider:
    def __init__(self, api_key: str, model: str, timeout: float):
        self.model = model
        self.client = AsyncOpenAI(api_key=api_key, timeout=timeout, max_retries=1) if api_key else None

    def _require_client(self) -> AsyncOpenAI:
        if self.client is None:
            raise LlmError("OpenAI API 키가 설정되지 않았습니다.")
        return self.client

    async def answer(self, message: str) -> str:
        client = self._require_client()
        try:
            response = await client.responses.create(model=self.model, instructions=INSTRUCTIONS, input=message)
        except OpenAIError as error:
            raise LlmError("답변을 생성하지 못했습니다.") from error
        text = response.output_text.strip()
        if not text:
            raise LlmError("답변을 생성하지 못했습니다.")
        return text

    async def check_model(self) -> str:
        """설정한 모델을 이 키로 쓸 수 있는지 확인합니다."""
        client = self._require_client()
        try:
            return (await client.models.retrieve(self.model)).id
        except OpenAIError as error:
            raise LlmError(f"모델을 사용할 수 없습니다: {self.model}") from error