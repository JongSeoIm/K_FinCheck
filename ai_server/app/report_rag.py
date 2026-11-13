"""사업보고서 RAG.

OpenDART 사업보고서 원문(수십만 자)을 나눠 벡터로 저장해 두고, 질문과 관련 있는 조각만 찾아 GPT 에 근거로 넣는다.

흐름: Spring(원문 ZIP) → XML 에서 목차(TITLE)별 섹션 추출 → 필요한 섹션만 남김
     → 섹션을 1,000자씩 200자 겹쳐 청크 → text-embedding-3-small 로 벡터화
     → MariaDB(report_index·report_chunk)에 청크·벡터 저장 (접수번호당 한 번, Spring API 경유)
     → 질문 벡터와 가까운 조각 k 개를 SQL(VEC_DISTANCE_COSINE)로 조회

저장소
- SpringReportStore (기본): Spring /api/rag/reports API 로 MariaDB 에 저장·검색한다. AI 서버는 DB 에 직접 붙지 않는다.
- ReportStore (REPORT_STORE=sqlite): 예전 방식. ai_server/data/reports.sqlite3 에 저장하고 파이썬에서 거리를 계산한다.

청크 기준
- 섹션(목차) 단위로 먼저 나눈다. 서로 다른 주제(사업 내용·위험·배당)가 한 조각에 섞이지 않게 하기 위해서다.
- 긴 섹션은 1,000자씩 나누고 200자를 겹친다. 문단이 조각 경계에서 끊겨도 앞뒤 문맥이 남는다.
- 조각 앞에 "[섹션 경로]"를 붙여 벡터에 주제 정보가 들어가게 한다.
- 재무제표 주석·임원·감사·회사 연혁·대주주 거래 같은 섹션은 뺀다. 수치는 실적 Tool 이 정확히 주고, 질문과 관계가 적어 비용만 늘기 때문이다.
- 숫자 칸만 이어진 표 조각(칸 글자의 60% 초과가 숫자)은 저장하지 않는다. 벡터 검색에서 엉뚱하게 뽑히기 때문이다.
  "DRAM | 77.4%" 처럼 이름이 붙은 표는 남긴다.

검색
- 질문에서 종목코드·연도 숫자를 빼고 "회사 연도 사업보고서 질문" 꼴로 벡터화한다. '왜' 질문은 원인 설명 어휘를 덧붙인다.
- 실적 이유 질문은 키워드 보충을 한다. '영업손실·적자전환', '매출 + 영향으로/약세/부진/악화' 가 든 조각을
  경영진단 항목 우선으로 최대 3개 앞에 두고, 나머지를 벡터 검색 결과로 채운다(합계 k개).
"""
from __future__ import annotations

import asyncio
import bisect
import html
import io
import json
import logging
import re
import threading
import zipfile
from dataclasses import dataclass
from pathlib import Path

import httpx

from app.rag import OpenAIEmbedder, chunk_text, cosine, lexical_similarity, open_db
from app.schemas import StockContext


logger = logging.getLogger(__name__)

SEARCH_K = 3                 # 모델에 넣고 화면에 보여 줄 근거 조각 수. 많으면 관련 없는 조각이 섞인다.
CHUNK_SIZE = 1000
CHUNK_OVERLAP = 200
REPORT_CHAR_LIMIT = 250_000   # 보고서 1건당 벡터화할 최대 글자 수 (비용 상한)
KEEP = ("회사의 개요", "사업의 내용", "경영진단", "분석의견", "배당", "위험", "주주", "연구개발", "주요 제품", "시장")
SKIP = ("재무제표", "주석", "감사", "임원", "직원", "계열회사", "이해관계자", "상세표", "전문가", "보수", "타법인",
        "연혁", "대주주", "거래내용")  # 회사 연혁·대주주 거래는 실적·사업 질문의 근거가 되지 않는다

TITLE = re.compile(r"<TITLE[^>]*>(.*?)</TITLE>", re.S | re.I)
SECTION_OPEN = re.compile(r"<SECTION-(\d)", re.I)


def is_report_question(message: str) -> bool:
    """사업보고서 원문으로 답할 질문인지. 수치 하나(주가·실적)는 Tool, 설명·분석은 RAG."""
    compact = message.casefold().replace(" ", "")
    # "리스크란?", "배당정책 뜻" 같은 용어 정의 질문은 개념 설명으로 둔다. "위험 요인이 뭐야?"는 보고서 질문이다.
    if re.search(r"뜻|정의|(?:이)?란\??$|(?:이)?란무엇", compact) and "사업보고서" not in compact:
        return False
    topics = ("사업보고서", "사업내용", "사업의내용", "무슨사업", "어떤사업", "주요사업", "주력사업", "사업구조", "사업부문",
              "주요제품", "위험요인", "리스크", "배당정책", "주주환원", "경영진", "경쟁", "점유율", "연구개발", "r&d",
              "생산능력", "전략", "향후계획", "사업전망")
    if any(word in compact for word in topics):
        return True
    finance = any(word in compact for word in ("실적", "매출", "영업이익", "순이익", "이익", "적자", "흑자", "손실"))
    cause = any(word in compact for word in ("왜", "이유", "원인", "배경", "요인", "때문", "어째서"))
    return finance and cause


@dataclass
class Section:
    path: str
    text: str


def decode_xml(raw: bytes) -> str:
    head = raw[:200].decode("ascii", "ignore")
    declared = re.search(r'encoding=["\']([^"\']+)', head)
    for encoding in (declared.group(1) if declared else None, "utf-8", "cp949"):
        if not encoding:
            continue
        try:
            return raw.decode(encoding)
        except (LookupError, UnicodeDecodeError):
            continue
    return raw.decode("utf-8", "replace")


def main_document(zip_bytes: bytes, receipt_no: str) -> str:
    """원문 ZIP 에서 본문 XML(접수번호.xml, 없으면 가장 큰 XML)을 꺼낸다. 감사보고서 등 첨부는 쓰지 않는다."""
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
        names = [name for name in archive.namelist() if name.lower().endswith(".xml")]
        if not names:
            raise ValueError("사업보고서 원문 XML 이 없습니다.")
        name = next((n for n in names if n.rsplit("/", 1)[-1] == f"{receipt_no}.xml"), None) \
            or max(names, key=lambda n: archive.getinfo(n).file_size)
        return decode_xml(archive.read(name))


def clean_text(fragment: str) -> str:
    text = re.sub(r"(?is)<(IMAGE|IMG|LIBRARY)\b[^>]*>.*?</\1>", " ", fragment)
    text = re.sub(r"(?i)</(TD|TH|TE|TU)>", " | ", text)
    text = re.sub(r"(?i)<(/P|BR|/TR|PGBRK|/TITLE)\b[^>]*>", "\n", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text).replace(" ", " ")
    lines = []
    for line in text.split("\n"):
        line = re.sub(r"[ \t]+", " ", line).strip(" |\t")
        if line and not re.fullmatch(r"[|\s-]*", line):
            lines.append(line)
    return "\n".join(lines)


def parse_sections(xml: str) -> list[Section]:
    """TITLE 태그를 목차로 삼아 섹션을 나눈다. 단계는 바로 앞 SECTION-n 태그로 정한다."""
    opens = [(m.start(), int(m.group(1))) for m in SECTION_OPEN.finditer(xml)]
    positions = [position for position, _ in opens]
    titles = list(TITLE.finditer(xml))
    stack: dict[int, str] = {}
    sections = []
    for index, match in enumerate(titles):
        title = clean_text(match.group(1)).replace("\n", " ").strip()
        if not title:
            continue
        before = bisect.bisect_left(positions, match.start()) - 1
        level = opens[before][1] if before >= 0 else 1
        stack = {lv: name for lv, name in stack.items() if lv < level}
        stack[level] = title
        end = titles[index + 1].start() if index + 1 < len(titles) else len(xml)
        body = clean_text(xml[match.end():end])
        if body:
            sections.append(Section(" > ".join(stack[lv] for lv in sorted(stack)), body))
    return sections


def select_sections(sections: list[Section]) -> list[Section]:
    wanted = [s for s in sections if any(k in s.path for k in KEEP) and not any(k in s.path for k in SKIP)]
    # 목차 이름이 달라 하나도 못 고르면 제외 대상만 빼고 쓴다.
    return wanted or [s for s in sections if not any(k in s.path for k in SKIP)]


_NUMERIC_CELL = re.compile(r"[\d,.\-()%\s△▲]+")
TABLE_SHARE_LIMIT = 0.6


def numeric_table_share(text: str) -> float:
    """표 칸(세로선으로 나뉜 칸) 글자 중 숫자만 있는 칸의 비율. 표가 아니면(칸 4개 미만) 0."""
    cells = [cell.strip() for cell in text.split("|") if cell.strip()]
    if len(cells) < 4:
        return 0.0
    total = sum(len(cell) for cell in cells)
    return sum(len(cell) for cell in cells if _NUMERIC_CELL.fullmatch(cell)) / total if total else 0.0


def build_chunks(sections: list[Section], size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP,
                 limit: int = REPORT_CHAR_LIMIT) -> list[tuple[str, str]]:
    """(섹션 경로, 청크 본문) 목록. 본문 앞에 섹션 경로를 붙인다. 숫자 표뿐인 조각은 뺀다."""
    chunks, total = [], 0
    for section in sections:
        for piece in chunk_text(section.text, size, overlap):
            if numeric_table_share(piece) > TABLE_SHARE_LIMIT:
                continue
            if total + len(piece) > limit:
                return chunks
            chunks.append((section.path, f"[{section.path}] {piece}"))
            total += len(piece)
    return chunks


class ReportStore:
    """사업보고서 청크·벡터 저장소 (SQLite, REPORT_STORE=sqlite). 접수번호 단위로 한 번만 만든다."""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with open_db(self._path) as db:
            db.execute("CREATE TABLE IF NOT EXISTS report (receipt_no TEXT PRIMARY KEY, stock_code TEXT NOT NULL,"
                       " report_name TEXT, business_year INTEGER, source_url TEXT, embedding_model TEXT NOT NULL,"
                       " chunk_count INTEGER NOT NULL, indexed_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            db.execute("CREATE TABLE IF NOT EXISTS report_chunk (receipt_no TEXT NOT NULL, chunk_index INTEGER NOT NULL,"
                       " section TEXT NOT NULL, text TEXT NOT NULL, vector TEXT, PRIMARY KEY (receipt_no, chunk_index))")

    async def indexed(self, receipt_no: str, model: str) -> bool:
        with self._lock, open_db(self._path) as db:
            row = db.execute("SELECT embedding_model FROM report WHERE receipt_no=?", (receipt_no,)).fetchone()
        return row is not None and row[0] == model

    async def save(self, meta: dict, model: str, chunks: list[tuple[str, str]], vectors: list[list[float]] | None) -> None:
        receipt = meta["receiptNo"]
        with self._lock, open_db(self._path) as db:
            db.execute("DELETE FROM report_chunk WHERE receipt_no=?", (receipt,))
            db.executemany("INSERT INTO report_chunk(receipt_no, chunk_index, section, text, vector) VALUES (?,?,?,?,?)",
                           [(receipt, i, section, text, json.dumps(vectors[i]) if vectors else None)
                            for i, (section, text) in enumerate(chunks)])
            db.execute("INSERT OR REPLACE INTO report(receipt_no, stock_code, report_name, business_year, source_url,"
                       " embedding_model, chunk_count) VALUES (?,?,?,?,?,?,?)",
                       (receipt, meta["stockCode"], meta.get("reportName"), meta.get("businessYear"),
                        meta.get("sourceUrl"), model, len(chunks)))

    def load(self, receipt_no: str) -> list[tuple[str, str, list[float] | None]]:
        with self._lock, open_db(self._path) as db:
            rows = db.execute("SELECT section, text, vector FROM report_chunk WHERE receipt_no=? ORDER BY chunk_index",
                              (receipt_no,)).fetchall()
        return [(section, text, json.loads(vector) if vector else None) for section, text, vector in rows]

    def reports(self) -> list[dict]:
        """MariaDB 로 옮길 때 쓰는 보고서 목록."""
        with self._lock, open_db(self._path) as db:
            rows = db.execute("SELECT receipt_no, stock_code, report_name, business_year, source_url, embedding_model"
                              " FROM report ORDER BY receipt_no").fetchall()
        return [{"receiptNo": r[0], "stockCode": r[1], "reportName": r[2], "businessYear": r[3], "sourceUrl": r[4],
                 "model": r[5]} for r in rows]

    async def search(self, receipt_no: str, query: list[float], k: int) -> list[tuple[float, str, str]]:
        rows = self.load(receipt_no)
        scored = [(cosine(query, vector), section, text) for section, text, vector in rows if vector]
        return sorted(scored, key=lambda item: item[0], reverse=True)[:k]

    async def texts(self, receipt_no: str) -> list[tuple[str, str]]:
        return [(section, text) for section, text, _ in self.load(receipt_no)]


class SpringReportStore:
    """사업보고서 청크·벡터 저장소 (MariaDB). Spring /api/rag/reports API 로 저장·검색한다.
    MariaDB 11.7 이상이면 검색 정렬을 SQL 의 VEC_DISTANCE_COSINE 으로 DB 가 한다."""

    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    def _client(self, timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=self._base_url, timeout=timeout, transport=self._transport)

    async def indexed(self, receipt_no: str, model: str) -> bool:
        async with self._client(10) as client:
            response = await client.get(f"/api/rag/reports/{receipt_no}")
        if response.status_code == 404:
            return False
        response.raise_for_status()
        return response.json().get("embeddingModel") == model

    async def save(self, meta: dict, model: str, chunks: list[tuple[str, str]], vectors: list[list[float]] | None) -> None:
        body = {"stockCode": meta["stockCode"], "reportName": meta.get("reportName"), "businessYear": meta.get("businessYear"),
                "sourceUrl": meta.get("sourceUrl"), "embeddingModel": model,
                "chunks": [{"section": section, "content": text, "embedding": vectors[i] if vectors else None}
                           for i, (section, text) in enumerate(chunks)]}
        async with self._client(120) as client:
            response = await client.put(f"/api/rag/reports/{meta['receiptNo']}", json=body)
        response.raise_for_status()

    async def search(self, receipt_no: str, query: list[float], k: int) -> list[tuple[float, str, str]]:
        async with self._client(20) as client:
            response = await client.post(f"/api/rag/reports/{receipt_no}/search", json={"embedding": query, "k": k})
        response.raise_for_status()
        return [(float(hit["score"]), hit["section"], hit["content"]) for hit in response.json().get("hits", [])]

    async def texts(self, receipt_no: str) -> list[tuple[str, str]]:
        async with self._client(20) as client:
            response = await client.get(f"/api/rag/reports/{receipt_no}/chunks")
        response.raise_for_status()
        return [(row["section"], row["content"]) for row in response.json()]


REASON_WORDS = re.compile(r"왜|이유|원인|때문|어째서|배경")  # "위험 요인"의 요인은 원인 질문이 아니다
CAUSE_WORDS = ("영향으로", "약세", "부진", "악화")


def build_query(company: str, year: int | None, message: str) -> str:
    """질문에서 종목코드·연도 숫자를 빼고 회사·연도를 앞에 붙여 검색 문장을 만든다."""
    words = re.sub(r"(?<!\d)\d{6}(?!\d)|(?<!\d)20\d{2}(?!\d)", " ", message)
    words = re.sub(r"\s+", " ", words).strip()
    # '왜' 질문은 숫자 표보다 원인을 설명하는 서술 문장과 가깝게 검색되도록 설명 어휘를 덧붙인다.
    hint = " 원인 영향으로 인한" if REASON_WORDS.search(message) else ""
    return f"{company} {str(year) + '년 ' if year else ''}사업보고서 {words}{hint}"


def keyword_terms(message: str) -> list:
    """실적 이유 질문에서 반드시 확인할 단어 묶음. 묶음끼리는 OR, 묶음 안은 모두 들어 있어야 한다(튜플은 그중 하나)."""
    if not REASON_WORDS.search(message):
        return []
    if re.search(r"적자|손실", message):
        return [["영업손실"], ["적자전환"]]
    groups: list = []
    for word in ("매출", "영업이익", "순이익"):
        if word in message or (word == "순이익" and "당기순" in message):
            # 경영진단은 '~ 영향으로/약세로 감소하였습니다' 꼴로 원인을 적는다(회사마다 표현이 다르다).
            groups.append([word, CAUSE_WORDS])
    return groups


def keyword_match(text: str, groups: list) -> bool:
    def has(word) -> bool:
        return word in text if isinstance(word, str) else any(w in text for w in word)
    return any(all(has(word) for word in group) for group in groups)


def _year_param(year: int | None) -> dict:
    return {} if year is None else {"year": year}


def report_year(message: str, today_year: int) -> int | None:
    """질문에서 사업연도를 찾는다. 없으면 None(최신 보고서). 둘 이상이면 ValueError."""
    years = {int(y) for y in re.findall(r"(?<!\d)(20\d{2})(?!\d)", message)}
    compact = message.replace(" ", "")
    if "재작년" in compact:
        years.add(today_year - 2)
    elif "작년" in compact or "지난해" in compact:
        years.add(today_year - 1)
    if len(years) > 1:
        raise ValueError("사업보고서 질문은 한 번에 한 사업연도씩 해 주세요. 예: SK하이닉스 2023년 실적 왜 나빠졌어?")
    year = next(iter(years), None)
    if year is not None and not 2015 <= year < today_year:
        raise ValueError(f"{year}년 사업보고서는 조회할 수 없습니다. 2015년부터 {today_year - 1}년까지 지원합니다.")
    return year


class BusinessReportTool:
    """Spring 의 사업보고서 API 호출. OpenDART 키는 Spring 에만 있다."""

    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._base_url = base_url
        self._transport = transport

    async def metadata(self, stock: StockContext, year: int | None = None) -> dict:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=20, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/business-report", params=_year_param(year))
            response.raise_for_status()
            meta = response.json()
        if meta.get("stockCode") != stock.symbol or not re.fullmatch(r"\d{14}", str(meta.get("receiptNo", ""))):
            raise ValueError("사업보고서 정보가 올바르지 않습니다.")
        if year is not None and meta.get("businessYear") != year:
            raise ValueError(f"{year}년 사업보고서가 아닙니다.")  # 다른 연도 보고서로 답하지 않는다.
        return meta

    async def document(self, stock: StockContext, year: int | None = None) -> bytes:
        async with httpx.AsyncClient(base_url=self._base_url, timeout=60, transport=self._transport) as client:
            response = await client.get(f"/api/stocks/{stock.symbol}/business-report/document", params=_year_param(year))
            response.raise_for_status()
            return response.content


class BusinessReportRag:
    def __init__(self, tool: BusinessReportTool, store: "ReportStore | SpringReportStore",
                 embedder: OpenAIEmbedder | None = None) -> None:
        self._tool = tool
        self._store = store
        self._embedder = embedder
        self._locks: dict[str, asyncio.Lock] = {}

    @property
    def _model(self) -> str:
        return getattr(self._embedder, "model", "text-embedding") if self._embedder else "lexical"

    async def metadata(self, stock: StockContext, year: int | None = None) -> dict:
        """색인하지 않고 보고서 정보(접수번호·사업연도)만 확인한다."""
        return await self._tool.metadata(stock, year)

    async def ensure_indexed(self, stock: StockContext, year: int | None = None, rebuild: bool = False) -> dict:
        meta = await self._tool.metadata(stock, year)
        receipt = meta["receiptNo"]
        lock = self._locks.setdefault(receipt, asyncio.Lock())
        async with lock:
            if rebuild or not await self._store.indexed(receipt, self._model):
                xml = main_document(await self._tool.document(stock, year), receipt)
                chunks = build_chunks(select_sections(parse_sections(xml)))
                if not chunks:
                    raise ValueError("사업보고서에서 읽을 수 있는 본문을 찾지 못했습니다.")
                vectors = await self._embedder.embed([text for _, text in chunks]) if self._embedder else None
                await self._store.save(meta, self._model, chunks, vectors)
                logger.info("사업보고서 색인 완료: %s %s, 청크 %d개", stock.symbol, receipt, len(chunks))
        return meta

    async def search(self, stock: StockContext, question: str, k: int = SEARCH_K, year: int | None = None,
                     keywords: list | None = None) -> tuple[dict, list[dict]]:
        meta = await self.ensure_indexed(stock, year)
        receipt = meta["receiptNo"]
        ranked = None
        if self._embedder is not None:
            try:
                query = (await self._embedder.embed([build_query(stock.name, meta.get("businessYear"), question)]))[0]
                ranked = await self._store.search(receipt, query, k) or None  # 저장소(MariaDB)가 가까운 조각 k개를 고른다.
            except Exception:  # 임베딩·검색 실패는 답변 실패로 만들지 않는다.
                logger.warning("사업보고서 벡터 검색 실패, 글자 비교로 대신합니다.", exc_info=True)
        if ranked is None:
            rows = await self._store.texts(receipt)
            ranked = sorted(((lexical_similarity(question, text), section, text) for section, text in rows),
                            key=lambda item: item[0], reverse=True)[:k]
        if keywords:
            # 벡터 검색이 놓치는 원인 문장을 보충한다. 경영진단 항목을 먼저, 그다음 보고서 순서.
            try:
                rows = await self._store.texts(receipt)
                found = [(1.0, section, text) for section, text in rows if keyword_match(text, keywords)]
                found = sorted(found, key=lambda item: "경영진단" not in item[1])[:3]
                seen = {text for _, _, text in found}
                ranked = (found + [item for item in ranked if item[2] not in seen])[:k]
            except Exception:
                logger.warning("사업보고서 키워드 보충 검색 실패, 벡터 검색 결과만 씁니다.", exc_info=True)
        hits = [{"section": section, "text": text.split("] ", 1)[-1], "score": round(score, 4)}
                for score, section, text in ranked if score > 0]
        return meta, hits
