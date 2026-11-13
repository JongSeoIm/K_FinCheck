import unittest

from app.policy_relevance import MockPolicyAnalyzer, PolicyRelevanceRequest, chunks, cosine


def request(title: str) -> PolicyRelevanceRequest:
    return PolicyRelevanceRequest(
        stockCode="005930", companyName="삼성전자", aliases=["삼성전자"],
        industryKeywords=["반도체", "HBM", "파운드리"], title=title,
        content="행사에서 삼성전자 임원이 AI 시대의 반도체 산업을 주제로 강연했다.",
    )


class PolicyRelevanceTest(unittest.IsolatedAsyncioTestCase):
    def test_cosine_and_chunk_limit(self):
        self.assertAlmostEqual(1.0, cosine([1.0, 0.0], [1.0, 0.0]))
        self.assertEqual(4, len(chunks("가" * 5000)))

    async def test_incidental_body_mention_is_not_relevant(self):
        result = await MockPolicyAnalyzer().analyze(
            request("산업현장을 이끄는 힘, 여성공학인의 도전과 성장을 지원한다"))
        self.assertFalse(result.relevant)
        self.assertEqual("IRRELEVANT", result.relationType)

    async def test_industry_policy_title_is_relevant(self):
        result = await MockPolicyAnalyzer().analyze(request("반도체 산업 세액공제 확대"))
        self.assertTrue(result.relevant)
        self.assertEqual("INDIRECT", result.relationType)


if __name__ == "__main__":
    unittest.main()
