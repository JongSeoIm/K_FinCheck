"""1단계. 조선비즈 이용 조건."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date


@dataclass(frozen=True)
class SourceTerms:
    source_id: str
    name: str
    homepage: str
    robots_url: str
    checked_on: date
    basis: str
    inquiry_status: str
    attribution: str

    def to_dict(self) -> dict:
        return {"sourceId": self.source_id, "name": self.name, "homepage": self.homepage,
                "robotsUrl": self.robots_url, "checkedOn": self.checked_on.isoformat(), "basis": self.basis,
                "inquiryStatus": self.inquiry_status, "attribution": self.attribution}


CHOSUNBIZ = SourceTerms(
    source_id="chosunbiz",
    name="조선비즈",
    homepage="https://biz.chosun.com",
    robots_url="https://biz.chosun.com/robots.txt",
    checked_on=date(2026, 11, 12),
    basis=("robots.txt Content-Signal: search=yes, ai-input=yes, ai-train=no / "
           "출처를 표시하고 원문을 링크하는 조건으로 AI 답변 인용 허용 / 사용자 요청에 따른 가져오기 허용"),
    inquiry_status="이용약관 원문이 공개되어 있지 않아 이용 범위를 문의하는 중이며, 답변 전까지 robots.txt 기준으로 운영",
    attribution="출처: 조선비즈",
)

# 조선비즈 공식 RSS(증권·산업·IT과학·전체). 피드마다 최근 기사 최대 100건이 들어 있다(2026-11-13 확인:
# 증권 약 4일, IT과학 약 10일, 산업·전체 약 2일치). 30분마다 받아 최근 5일치를 메모리에 쌓는다.
FEEDS: tuple[tuple[str, str], ...] = (
    ("증권", "https://biz.chosun.com/arc/outboundfeeds/rss/category/stock/?outputType=xml"),
    ("산업", "https://biz.chosun.com/arc/outboundfeeds/rss/category/industry/?outputType=xml"),
    ("IT과학", "https://biz.chosun.com/arc/outboundfeeds/rss/category/it-science/?outputType=xml"),
    ("전체", "https://biz.chosun.com/arc/outboundfeeds/rss/?outputType=xml"),
)
NEWS_DAYS = 5   # 발행 후 5일이 지난 기사는 받지 않고, 보관 중이면 지운다


def ai_input_allowed(content_signal: dict[str, str] | None) -> bool:
    """robots.txt에 ai-input=yes 표시가 있을 때만 AI 요약을 허용한다. 표시가 없거나 no면 중단."""
    return content_signal is not None and content_signal.get("ai-input") == "yes"
