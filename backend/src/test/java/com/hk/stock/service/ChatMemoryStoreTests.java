package com.hk.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hk.stock.dtos.ChatMessageDto;

class ChatMemoryStoreTests {

	private static ChatMessageDto msg(String role, String content) {
		return new ChatMessageDto(role, content, null, List.of(), "2026-11-04T10:00:00+09:00");
	}

	@Test
	void 최근_메시지_10개만_유지한다() {
		ChatMemoryStore store = new ChatMemoryStore();
		for (int i = 1; i <= 6; i++) {
			store.append(1L, "005930", msg("user", "질문" + i), msg("assistant", "답변" + i));
		}
		List<ChatMessageDto> messages = store.get(1L, "005930");
		assertThat(messages).hasSize(10);
		//12개 중 가장 오래된 질문1·답변1 이 빠진다
		assertThat(messages.get(0).content()).isEqualTo("질문2");
		assertThat(messages.get(9).content()).isEqualTo("답변6");
	}

	@Test
	void 계정과_종목별로_분리된다() {
		ChatMemoryStore store = new ChatMemoryStore();
		store.append(1L, "005930", msg("user", "A 삼성"));
		store.append(1L, "000660", msg("user", "A 하이닉스"));
		store.append(2L, "005930", msg("user", "B 삼성"));

		assertThat(store.get(1L, "005930")).extracting(ChatMessageDto::content).containsExactly("A 삼성");
		assertThat(store.get(2L, "005930")).extracting(ChatMessageDto::content).containsExactly("B 삼성");
		assertThat(store.get(2L, "000660")).isEmpty();
	}

	@Test
	void 새_대화와_로그아웃은_문맥을_지운다() {
		ChatMemoryStore store = new ChatMemoryStore();
		store.append(1L, "005930", msg("user", "A 삼성"));
		store.append(1L, "000660", msg("user", "A 하이닉스"));
		store.append(2L, "005930", msg("user", "B 삼성"));

		store.clear(1L, "005930");	//새 대화
		assertThat(store.get(1L, "005930")).isEmpty();
		assertThat(store.get(1L, "000660")).hasSize(1);

		store.clearUser(1L);	//로그아웃
		assertThat(store.get(1L, "000660")).isEmpty();
		assertThat(store.get(2L, "005930")).hasSize(1);	//다른 계정은 그대로
	}
}
