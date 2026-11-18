package com.kfincheck.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="stream.enabled=false")
@Transactional
class ChatHistoryTest {
    @Autowired ChatHistory history;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;

    private long user() {
        String login = "chat_test_" + java.util.UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO app_user(login_id,password_hash,nickname) VALUES(?,?,?)", login, "test-only", "대화 테스트");
        return jdbc.queryForObject("SELECT user_id FROM app_user WHERE login_id=?", Long.class, login);
    }

    @Test void restoresMessagesAndCardsAndKeepsOnlyTenContextMessages() throws Exception {
        long user = user();
        var room = history.create(user, new ChatModels.Stock("005930", "삼성전자"));
        var payload = json.readTree("{\"type\":\"quote\",\"data\":{\"price\":70000}}");
        var answer = new ChatModels.AiResponse("answered", "시세 조회 결과입니다.", java.util.List.of(), payload, "삼성전자 현재가");
        for (int i=0; i<6; i++) history.appendPair(room.conversationId(), "질문 " + i, answer);
        var recreated = new ChatHistory(jdbc, json, dataSource);
        assertThat(recreated.messages(user, room.conversationId())).hasSize(12);
        assertThat(recreated.context(room.conversationId())).hasSize(10);
        assertThat(recreated.context(room.conversationId()).getFirst().content()).isEqualTo("삼성전자 현재가");
        assertThat(recreated.messages(user, room.conversationId()).get(1).response().presentation()).isEqualTo(payload);
        var fresh = history.create(user, new ChatModels.Stock("005930", "삼성전자"));
        assertThat(history.context(fresh.conversationId())).isEmpty();
        assertThat(history.list(user, "005930")).hasSize(2);
    }

    @Test void isolatesOwnersAndDeletesOnlySelectedConversation() {
        long first = user(), second = user();
        var room = history.create(first, new ChatModels.Stock("005930", "삼성전자"));
        var kept = history.create(first, new ChatModels.Stock("000660", "SK하이닉스"));
        history.appendPair(room.conversationId(), "질문", new ChatModels.AiResponse("answered", "답변", java.util.List.of()));
        assertThat(history.list(second, "005930")).isEmpty();
        assertThatThrownBy(() -> history.messages(second, room.conversationId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> history.remove(second, room.conversationId())).isInstanceOf(ResponseStatusException.class);
        assertThat(history.messages(first, room.conversationId())).hasSize(2);
        history.remove(first, room.conversationId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE conversation_id=?", Integer.class, room.conversationId())).isZero();
        assertThat(history.owned(first, kept.conversationId(), false)).isNotNull();
    }
}
