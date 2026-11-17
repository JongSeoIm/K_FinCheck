"""사업보고서 RAG 를 MariaDB 저장소(Spring /api/rag/reports 경유)로 돌리는 경로."""
import json
import math
import unittest
from types import SimpleNamespace

import httpx

from app.rag import OpenAIEmbedder
from app.report_rag import BusinessReportRag, BusinessReportTool, SpringReportStore
from app.schemas import StockContext
from app.service import ChatService
from tests.test_report_rag import RECEIPT, FakeEmbeddings, spring
from tests.test_service import RecordingProvider, request


class FakeRagApi:
    """Spring ReportVectorController 흉내. report_index·report_chunk 대신 dict 에 저장한다."""

    def __init__(self, report_calls):
        self.reports = {}
        self.calls = []
        self._report = spring(report_calls)

    def __call__(self, req: httpx.Request) -> httpx.Response:
        path = req.url.path
        if not path.startswith("/api/rag/reports/"):
            return self._report.handler(req)
        self.calls.append(f"{req.method} {path}")
        receipt = path.split("/")[4]
        saved = self.reports.get(receipt)
        if req.method == "GET" and path.endswith("/chunks"):
            return httpx.Response(200, json=[{"section": c["section"], "content": c["content"]} for c in saved["chunks"]])
        if req.method == "GET":
            if saved is None:
                return httpx.Response(404, json={"code": "NOT_INDEXED"})
            return httpx.Response(200, json={"receiptNo": receipt, "embeddingModel": saved["embeddingModel"],
                                             "chunkCount": len(saved["chunks"]), "searchMode": "MariaDB VECTOR"})
        if req.method == "PUT":
            self.reports[receipt] = json.loads(req.content)
            return httpx.Response(204)
        if req.method == "POST" and path.endswith("/search"):
            body = json.loads(req.content)
            query = body["embedding"]
            def score(vector):
                dot = sum(a * b for a, b in zip(query, vector))
                return dot / (math.sqrt(sum(a * a for a in query)) * math.sqrt(sum(b * b for b in vector)))
            hits = sorted(({"section": c["section"], "content": c["content"], "score": score(c["embedding"])}
                           for c in saved["chunks"] if c["embedding"]), key=lambda h: h["score"], reverse=True)
            return httpx.Response(200, json={"searchMode": "MariaDB VECTOR", "hits": hits[:body["k"]]})
        return httpx.Response(405)


class MariaDbReportStoreTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.report_calls = []
        self.api = FakeRagApi(self.report_calls)
        transport = httpx.MockTransport(self.api)
        self.embeddings = FakeEmbeddings()
        self.rag = BusinessReportRag(BusinessReportTool("http://spring", transport),
                                     SpringReportStore("http://spring", transport),
                                     OpenAIEmbedder(SimpleNamespace(embeddings=self.embeddings)))

    async def test_index_is_saved_to_db_once_and_search_runs_in_db(self):
        service = ChatService(RecordingProvider(), report_rag=self.rag)
        result = await service.ask(request("SK하이닉스 위험 요인이 뭐야?"))
        self.assertEqual("report", result.presentation.type)
        self.assertIn("기타 참고사항", result.presentation.data["chunks"][0]["section"])
        saved = self.api.reports[RECEIPT]
        self.assertEqual("000660", saved["stockCode"])
        self.assertTrue(all(len(chunk["embedding"]) == 5 for chunk in saved["chunks"]))

        await service.ask(request("SK하이닉스 배당 정책 알려줘"))
        self.assertEqual(1, self.api.calls.count(f"PUT /api/rag/reports/{RECEIPT}"))
        self.assertEqual(2, self.api.calls.count(f"POST /api/rag/reports/{RECEIPT}/search"))
        self.assertEqual(1, sum(c.startswith("/api/stocks/000660/business-report/document") for c in self.report_calls))

    async def test_without_embedder_uses_db_texts_and_lexical_search(self):
        transport = httpx.MockTransport(self.api)
        rag = BusinessReportRag(BusinessReportTool("http://spring", transport), SpringReportStore("http://spring", transport))
        meta, hits = await rag.search(StockContext(symbol="000660", name="SK하이닉스"), "배당 정책")
        self.assertIn("배당", hits[0]["section"])
        self.assertIn(f"GET /api/rag/reports/{RECEIPT}/chunks", self.api.calls)
        self.assertIsNone(self.api.reports[RECEIPT]["chunks"][0]["embedding"])

    async def test_db_search_failure_falls_back_to_lexical(self):
        def broken(req):
            if req.method == "POST":
                return httpx.Response(500)
            return self.api(req)
        transport = httpx.MockTransport(broken)
        rag = BusinessReportRag(BusinessReportTool("http://spring", transport), SpringReportStore("http://spring", transport),
                                OpenAIEmbedder(SimpleNamespace(embeddings=FakeEmbeddings())))
        meta, hits = await rag.search(StockContext(symbol="000660", name="SK하이닉스"), "배당 정책")
        self.assertIn("배당", hits[0]["section"])


if __name__ == "__main__":
    unittest.main()
