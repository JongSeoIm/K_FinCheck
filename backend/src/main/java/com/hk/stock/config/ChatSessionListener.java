package com.hk.stock.config;

import org.springframework.stereotype.Component;

import com.hk.stock.service.ChatMemoryStore;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

//로그아웃(세션 삭제)·세션 만료 시 그 회원의 AI 대화 문맥을 지운다
@Component
public class ChatSessionListener implements HttpSessionListener {

	private final ChatMemoryStore memory;

	public ChatSessionListener(ChatMemoryStore memory) {
		this.memory = memory;
	}

	@Override
	public void sessionDestroyed(HttpSessionEvent event) {
		Object userId = event.getSession().getAttribute(LoginInterceptor.LOGIN_USER_ID);
		if (userId instanceof Long id) memory.clearUser(id);
	}
}
