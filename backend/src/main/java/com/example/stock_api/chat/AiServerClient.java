package com.example.stock_api.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.stock_api.common.ApiException;

import lombok.extern.slf4j.Slf4j;

/** FastAPI AI 서버 호출. 화면은 Spring 만 호출하고 OpenAI 키는 AI 서버에만 둡니다. */
@Slf4j
@Component
public class AiServerClient {

    private final RestClient rest;

    public AiServerClient(@Value("${ai.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(28000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 대화 기록은 Spring 이 DB 에 보관하고, AI 서버에는 답변에 필요한 만큼만 보냅니다 */
    public Map<String, Object> ask(long userId, ChatRequests.Ask ask, List<Map<String, String>> history, Object context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("conversationId", ask.conversationId());
        body.put("message", ask.message());
        body.put("stock", Map.of("symbol", ask.stock().symbol(), "name", ask.stock().name()));
        body.put("history", history);
        body.put("context", context);
        try {
            return rest.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (ResourceAccessException e) {
            log.warn("AI 서버 연결 실패: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RestClientException e) {
            log.warn("AI 서버 응답 오류: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", "AI 답변을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
