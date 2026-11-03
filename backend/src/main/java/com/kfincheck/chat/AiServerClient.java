package com.kfincheck.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiServerClient {
    private final RestClient restClient;

    public AiServerClient(RestClient aiServerRestClient) {
        this.restClient = aiServerRestClient;
    }

    ChatModels.AiResponse ask(ChatModels.AiRequest request) {
        return restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(ChatModels.AiResponse.class);
    }
}
