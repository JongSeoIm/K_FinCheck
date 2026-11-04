package com.example.stock_api.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 로그인한 사용자의 질문을 Python AI 서버(FastAPI)에 전달하고, 최근 대화를 함께 보냅니다.
 * 사용자 식별은 서버 세션의 회원 번호로만 하고, 브라우저가 보낸 사용자 정보는 쓰지 않습니다.
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    public static final int MAX_QUESTION_LENGTH = 1000;

    public static class ChatException extends RuntimeException {
        private final HttpStatus status;

        public ChatException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final RestClient client;
    private final String internalKey;
    private final ChatMemory memory;
    // 같은 사용자가 답변을 기다리는 중에 또 보내지 못하게 합니다. (OpenAI 호출 비용 보호)
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ChatService(@Value("${ai.server-url:http://127.0.0.1:8000}") String serverUrl,
                       @Value("${ai.internal-key:}") String internalKey,
                       @Value("${ai.timeout-seconds:60}") int timeoutSeconds,
                       ChatMemory memory) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.client = RestClient.builder().baseUrl(serverUrl).requestFactory(factory).build();
        this.internalKey = internalKey;
        this.memory = memory;
    }

    public Map<String, Object> ask(long userId, String message, String stockCode) {
        String question = message == null ? "" : message.strip();
        if (question.isEmpty()) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "질문을 입력해 주세요.");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "질문은 " + MAX_QUESTION_LENGTH + "자 이내로 입력해 주세요.");
        }
        String code = stockCode != null && stockCode.matches("\\d{6}") ? stockCode : null;

        if (!inFlight.add(userId)) {
            throw new ChatException(HttpStatus.TOO_MANY_REQUESTS, "이전 질문에 답변하는 중입니다. 잠시만 기다려 주세요.");
        }
        try {
            List<Map<String, String>> history = new ArrayList<>();
            for (ChatMemory.Message m : memory.get(userId)) {
                history.add(Map.of("role", m.role(), "content", m.content()));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", question);
            body.put("history", history);
            body.put("stockCode", code);

            Map<String, Object> result;
            try {
                RestClient.RequestBodySpec spec = client.post().uri("/chat");
                if (!internalKey.isBlank()) {
                    spec = spec.header("X-Internal-Key", internalKey);
                }
                result = spec.body(body).retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                // 내부 주소·키가 담길 수 있어 상세 내용은 사용자에게 보내지 않습니다.
                LOG.warn("AI 서버 호출 실패: {}", e.getClass().getSimpleName());
                throw new ChatException(HttpStatus.BAD_GATEWAY, "AI 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
            if (result == null || !(result.get("answer") instanceof String answer)) {
                throw new ChatException(HttpStatus.BAD_GATEWAY, "AI 서버 응답을 읽지 못했습니다.");
            }

            String status = String.valueOf(result.get("status"));
            @SuppressWarnings("unchecked")
            List<Object> sources = result.get("sources") instanceof List<?> list ? (List<Object>) list : List.of();

            // 정상 답변과 추가 질문 요청만 대화 문맥에 남깁니다. (오류·범위 밖 질문은 남기지 않음)
            if ("answered".equals(status) || "need_more_info".equals(status)) {
                memory.append(userId,
                        new ChatMemory.Message("user", question, List.of()),
                        new ChatMemory.Message("assistant", answer, sources));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", status);
            response.put("answer", answer);
            response.put("sources", sources);
            return response;
        } finally {
            inFlight.remove(userId);
        }
    }

    public List<ChatMemory.Message> history(long userId) {
        return memory.get(userId);
    }

    public void reset(long userId) {
        memory.clear(userId);
    }
}
