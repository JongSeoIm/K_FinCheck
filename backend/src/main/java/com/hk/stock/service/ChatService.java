package com.hk.stock.service;

import java.net.SocketTimeoutException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.config.AiProperties;
import com.hk.stock.dtos.ChatConversationDto;
import com.hk.stock.dtos.ChatMessageDto;
import com.hk.stock.dtos.StockDto;
import com.hk.stock.market.MarketHours;

/**
 * AI 챗봇: 브라우저 → Spring(로그인 확인·대화 기록 저장, 최근 메시지 10개를 문맥으로 전달) → FastAPI(질문 분류·Tool 조회·OpenAI 답변)
 * 응답 형식: {"conversationId","status","answer","sources":[{provider,label,basisAt,url}],"visuals":[카드·표·그래프·링크용 조회 자료]}
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);
	//대화 기록에 남기는 응답. 자료 없음·조회 실패 안내(unavailable)도 받은 답변이므로 남긴다
	//AI 답변 생성 실패(error)와 AI 서버 오류·시간 초과는 답변을 받지 못한 것이라 남기지 않는다
	private static final Set<String> REMEMBERED = Set.of("answered", "need_more_info", "out_of_scope", "unavailable");

	private final AiProperties props;
	private final StockService stockService;
	private final ChatHistoryStore history;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public ChatService(AiProperties props, StockService stockService, ChatHistoryStore history,
			ObjectMapper objectMapper, RestClient.Builder builder) {
		this.props = props;
		this.stockService = stockService;
		this.history = history;
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(3_000);
		factory.setReadTimeout(props.getTimeoutSeconds() * 1000);
		this.restClient = builder.requestFactory(factory).build();
	}

	public Map<String, Object> ask(Long userId, String symbol, String conversationId, String question) {
		StockDto stock = stockService.getStock(symbol);	//지원하지 않는 종목이면 404
		String text = question.trim();
		//다른 종목의 대화방에 섞이지 않게 한다
		if (!symbol.equals(history.room(userId, conversationId).getStockCode())) throw new ChatHistoryStore.ConversationNotFoundException();
		List<ChatMessageDto> context = history.context(conversationId);

		Map<String, Object> body = new HashMap<>();
		body.put("question", text);
		body.put("symbol", stock.getSymbol());
		body.put("stockName", stock.getName());
		body.put("history", context.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());

		Map<String, Object> answer = callAiServer(body);
		String status = String.valueOf(answer.getOrDefault("status", "unavailable"));
		String answerText = String.valueOf(answer.getOrDefault("answer", ""));
		List<Map<String, Object>> sources = mapsOf(answer.get("sources"));
		List<Map<String, Object>> visuals = mapsOf(answer.get("visuals"));

		if (isRemembered(status)) {
			String now = now();
			history.append(userId, conversationId,
					new ChatMessageDto("user", text, null, List.of(), List.of(), now),
					new ChatMessageDto("assistant", answerText, status, sources, visuals, now));
		}

		Map<String, Object> result = new HashMap<>();
		result.put("conversationId", conversationId);
		result.put("status", status);
		result.put("answer", answerText);
		result.put("sources", sources);
		result.put("visuals", visuals);
		return result;
	}

	public ChatConversationDto create(Long userId, String symbol) {
		StockDto stock = stockService.getStock(symbol);	//지원하지 않는 종목이면 404
		return history.create(userId, stock.getSymbol(), stock.getName());
	}

	public List<ChatConversationDto> conversations(Long userId, String symbol) {
		stockService.getStock(symbol);
		return history.rooms(userId, symbol);
	}

	public List<ChatMessageDto> messages(Long userId, String conversationId) {
		history.room(userId, conversationId);
		return history.restore(conversationId);
	}

	public void delete(Long userId, String conversationId) {
		history.delete(userId, conversationId);
	}

	private Map<String, Object> callAiServer(Map<String, Object> body) {
		try {
			RestClient.RequestBodySpec request = restClient.post()
					.uri(props.getServerUrl() + "/chat")
					.contentType(MediaType.APPLICATION_JSON);
			if (props.getInternalKey() != null && !props.getInternalKey().isBlank()) {
				request.header("X-Internal-Key", props.getInternalKey());
			}
			String raw = request.body(body).retrieve().body(String.class);
			Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
			if (parsed == null || !parsed.containsKey("answer")) {
				throw new AiServerException(HttpStatus.BAD_GATEWAY, "AI 서버 응답 형식이 올바르지 않습니다.");
			}
			return parsed;
		} catch (RestClientResponseException e) {
			log.warn("AI 서버 오류: {} {}", e.getStatusCode().value(), e.getResponseBodyAsString());
			throw new AiServerException(HttpStatus.BAD_GATEWAY, "AI 서버에서 오류가 발생했습니다. (" + e.getStatusCode().value() + ")");
		} catch (ResourceAccessException e) {
			//연결은 됐는데 답변이 늦은 경우(Read timed out)만 504, 연결 자체가 안 되면 503
			if (e.getCause() instanceof SocketTimeoutException && String.valueOf(e.getMessage()).contains("Read timed out")) {
				throw new AiServerException(HttpStatus.GATEWAY_TIMEOUT, "AI 답변이 너무 오래 걸립니다. 잠시 후 다시 질문해 주세요.");
			}
			log.warn("AI 서버 연결 실패: {}", e.getMessage());
			throw new AiServerException(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없습니다.");
		} catch (RestClientException | java.io.IOException e) {
			log.warn("AI 서버 응답 처리 실패: {}", e.getMessage());
			throw new AiServerException(HttpStatus.BAD_GATEWAY, "AI 서버 응답을 읽지 못했습니다.");
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> mapsOf(Object value) {
		List<Map<String, Object>> maps = new ArrayList<>();
		if (value instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) maps.add((Map<String, Object>) map);
			}
		}
		return maps;
	}

	static boolean isRemembered(String status) {
		return REMEMBERED.contains(status);
	}

	private static String now() {
		return MarketHours.now().truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString();
	}

	public static class AiServerException extends RuntimeException {
		private final HttpStatus status;

		public AiServerException(HttpStatus status, String message) {
			super(message);
			this.status = status;
		}

		public HttpStatus getStatus() {
			return status;
		}
	}
}
