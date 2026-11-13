package com.kfincheck.policy;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PolicyRelevanceService {
    static final String ANALYZER_VERSION = "embedding-context-v1";
    private static final int MAX_AI_ANALYSES_PER_RUN = 3;

    private final IndustryPolicyRepository repository;
    private final PolicyRelevanceClient client;

    public PolicyRelevanceService(IndustryPolicyRepository repository, PolicyRelevanceClient client) {
        this.repository = repository;
        this.client = client;
    }

    public int analyzePending() {
        int analyzedByAi = 0;
        for (IndustryPolicyRepository.StoredDocument document : repository.findRecent(500)) {
            for (var entry : IndustryPolicyService.profiles().entrySet()) {
                String stockCode = entry.getKey();
                IndustryPolicyService.Profile profile = entry.getValue();
                if (repository.hasRelevance(document.id(), stockCode, ANALYZER_VERSION)) continue;

                IndustryPolicyService.Relevance candidate = IndustryPolicyService.relevance(
                        profile, document.title(), document.content());
                if (candidate.score() == 0) {
                    repository.saveRelevance(document.id(), stockCode, new IndustryPolicyRepository.RelevanceAnalysis(
                            false, "IRRELEVANT", 0, null, "제목과 도입부가 해당 기업·산업 정책을 다루지 않음",
                            ANALYZER_VERSION, Instant.now()));
                    continue;
                }
                if (analyzedByAi >= MAX_AI_ANALYSES_PER_RUN) continue;

                PolicyRelevanceClient.AnalysisResponse response = client.analyze(
                        new PolicyRelevanceClient.AnalysisRequest(stockCode, profile.name(), profile.aliases(),
                                profile.keywords(), document.title(), document.content()));
                boolean validRelation = "DIRECT".equals(response.relationType())
                        || "INDIRECT".equals(response.relationType());
                boolean relevant = response.relevant() && validRelation && response.relevanceScore() >= 70;
                repository.saveRelevance(document.id(), stockCode, new IndustryPolicyRepository.RelevanceAnalysis(
                        relevant, relevant ? response.relationType() : "IRRELEVANT",
                        Math.max(0, Math.min(100, response.relevanceScore())), response.embeddingSimilarity(),
                        response.reason(), ANALYZER_VERSION, Instant.now()));
                analyzedByAi++;
            }
        }
        return analyzedByAi;
    }
}
