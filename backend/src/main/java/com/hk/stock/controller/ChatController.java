package com.hk.stock.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.hk.stock.config.LoginInterceptor;
import com.hk.stock.dtos.ChatMessageDto;
import com.hk.stock.dtos.ChatRequest;
import com.hk.stock.service.ChatService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

//AI 챗봇 (LoginInterceptor 가 로그인 여부를 먼저 확인한다. 로그인한 사용자만 이용)
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatController {

	private static final String SYMBOL_REGEX = "\\d{6}";
	private static final String SYMBOL_MESSAGE = "종목코드는 6자리 숫자입니다.";

	@Autowired
	private ChatService chatService;

	//질문: POST /api/chat/messages {"symbol":"005930","question":"..."} → {"status","answer","sources"}
	@PostMapping("/messages")
	public Map<String, Object> ask(@SessionAttribute(LoginInterceptor.LOGIN_USER_ID) Long userId,
			@Valid @RequestBody ChatRequest body) {
		return chatService.ask(userId, body.getSymbol(), body.getQuestion());
	}

	//현재 기억 중인 대화 (최근 10개): GET /api/chat/conversations/005930 → {"messages":[...]}
	@GetMapping("/conversations/{symbol}")
	public Map<String, List<ChatMessageDto>> conversation(@SessionAttribute(LoginInterceptor.LOGIN_USER_ID) Long userId,
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, List<ChatMessageDto>> map = new HashMap<>();
		map.put("messages", chatService.conversation(userId, symbol));
		return map;
	}

	//새 대화 (문맥 삭제): DELETE /api/chat/conversations/005930 → 204
	@DeleteMapping("/conversations/{symbol}")
	public ResponseEntity<Void> clear(@SessionAttribute(LoginInterceptor.LOGIN_USER_ID) Long userId,
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		chatService.clear(userId, symbol);
		return ResponseEntity.noContent().build();
	}
}
