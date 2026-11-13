package com.kfincheck.policy;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class PolicyRelevanceClient {
    private final RestClient restClient;

    public PolicyRelevanceClient(RestClient aiServerRestClient) {
        this.restClient = aiServerRestClient;
    }

    AnalysisResponse analyze(AnalysisRequest request) {
        return restClient.post().uri("/api/policy/relevance").body(request).retrieve()
                .body(AnalysisResponse.class);
    }

    record AnalysisRequest(String stockCode, String companyName, List<String> aliases,
                           List<String> industryKeywords, String title, String content) { }
    record AnalysisResponse(boolean relevant, String relationType, int relevanceScore,
                            double embeddingSimilarity, String reason, String analyzerVersion) { }
}
