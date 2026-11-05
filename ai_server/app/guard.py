"""입력과 답변 검사. LLM 지침만 믿지 않고 서버에서 한 번 더 확인합니다."""

import re

INJECTION = re.compile(
    r"(이전|앞선|위의?)\s*(지시|규칙|지침|명령).{0,8}(무시|잊어)|시스템\s*프롬프트|system\s*prompt|"
    r"프롬프트.{0,8}(보여|알려|출력)|ignore\s+(all\s+|previous\s+)*instructions",
    re.IGNORECASE,
)
MARKDOWN_LINE = re.compile(r"^\s*(#{1,6}\s+|[-*+]\s+|\d+\.\s+|>\s+)", re.MULTILINE)
GUARANTEE = re.compile(r"(수익|이익|상승).{0,6}보장|무조건\s*(오릅|오른|상승|벌)|반드시\s*(오릅|오른|상승|벌)")
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")
YEAR_TEXT = re.compile(r"20\d{2}년")  # 기준 시점의 연도는 조회 수치가 아닙니다
BLOCKED_REPLY = "금융 정보 안내와 관계없는 지시는 처리할 수 없습니다. 주식이나 기업에 관해 질문해 주세요."


def is_blocked(message: str) -> bool:
    return bool(INJECTION.search(message))


def clean_answer(answer: str) -> str:
    """Markdown 기호를 없애 일반 문장으로 만듭니다."""
    text = MARKDOWN_LINE.sub("", answer).replace("**", "").replace("__", "").replace("`", "")
    lines = [line for line in text.splitlines() if not line.strip().startswith("|")]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def has_guarantee(answer: str) -> bool:
    return bool(GUARANTEE.search(answer))


def numbers_supported(answer: str, facts: str) -> bool:
    """답변의 네 자리 이상 숫자가 조회 결과에 있는지 확인합니다."""
    known = {n.replace(",", "") for n in NUMBER.findall(facts)}
    for number in NUMBER.findall(YEAR_TEXT.sub("", answer)):
        plain = number.replace(",", "")
        if len(plain.split(".")[0]) >= 4 and plain not in known:
            return False
    return True
