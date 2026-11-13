"""실적 '이유' 질문 처리: 적자·흑자 여부는 조회 수치로 판정하고, 원인은 지어내지 않는다."""
from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation

REASON_PATTERN = re.compile(r"왜|이유|원인|때문|어째서")


def is_reason_question(message: str) -> bool:
    return bool(REASON_PATTERN.search(message))


def profit_status(report: dict) -> dict[str, str]:
    """영업이익·당기순이익의 '적자'/'흑자'. 값이 없거나 0이면 판정하지 않는다."""
    result: dict[str, str] = {}
    for metric in report.get("metrics", []):
        label = metric.get("label", "")
        if "영업이익" not in label and "순이익" not in label:
            continue
        try:
            amount = Decimal(metric["current"]) if metric.get("current") is not None else None
        except InvalidOperation:
            amount = None
        if amount is None or amount == 0:
            continue
        result[label] = "적자" if amount < 0 else "흑자"
    return result


def reason_answer(report: dict, about_loss: bool = True) -> str:
    name, year = report["name"], report["businessYear"]
    if not about_loss:
        # 적자·흑자를 묻지 않은 질문(매출·이익 변동 이유 등)에는 손익 판정 문구를 붙이지 않는다.
        return (f"{name}의 {year}년 연결 연간 실적 수치는 아래 표와 같습니다. 다만 현재 조회하는 공시 수치에는 숫자만 있고 "
                "그 원인은 들어 있지 않아 이유는 확인해 드릴 수 없습니다. 원인은 사업보고서의 경영진단 항목이나 당시 기사에서 확인할 수 있어요.")
    status = profit_status(report)
    losses = [label for label, value in status.items() if value == "적자"]
    if losses:
        head = f"{name}의 {year}년 연결 연간 실적에서 {', '.join(losses)} 항목이 적자입니다."
    elif status:
        head = f"{name}의 {year}년 연결 연간 실적에서는 {', '.join(status)} 항목이 모두 흑자로 조회됩니다. 적자가 아니에요."
    else:
        head = f"{name}의 {year}년 연결 연간 실적에서 적자·흑자 여부를 판단할 수 있는 수치를 확인하지 못했습니다."
    return (head + " 다만 현재 조회하는 공시 수치에는 숫자만 있고 그 원인은 들어 있지 않아 이유는 확인해 드릴 수 없습니다. "
            "원인은 사업보고서의 경영진 분석 항목이나 당시 기사에서 확인할 수 있어요. 수치와 전년 대비는 아래 표를 참고해 주세요.")
