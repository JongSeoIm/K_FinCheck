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

    @Test void erasesArticleTitlesAndSummariesAfter24Hours() throws Exception {
        long user = user();
        var room = history.create(user, new ChatModels.Stock("005930", "삼성전자"));
        String id = room.conversationId();
        var news = json.readTree("{\"type\":\"news\",\"data\":{\"name\":\"삼성전자\",\"articles\":[{\"title\":\"비밀 기사 제목\",\"link\":\"https://biz.chosun.com/a/1\"}]}}");
        var summary = json.readTree("{\"type\":\"news_summary\",\"data\":{\"title\":\"비밀 기사 제목\",\"link\":\"https://biz.chosun.com/a/1\"}}");
        var composite = json.readTree("{\"type\":\"composite\",\"data\":{\"sections\":[{\"type\":\"quote\",\"data\":{\"price\":70000}},"
                + "{\"type\":\"news\",\"data\":{\"articles\":[{\"title\":\"비밀 기사 제목\",\"link\":\"https://biz.chosun.com/a/1\"}]}}]}}");
        var quote = json.readTree("{\"type\":\"quote\",\"data\":{\"price\":70000}}");
        history.appendPair(id, "관련 기사 알려줘", new ChatModels.AiResponse("answered", "비밀 기사 제목 관련 답변", java.util.List.of(), news, null));
        history.appendPair(id, "1번 기사 요약해줘", new ChatModels.AiResponse("answered", "'비밀 기사 제목' 기사를 AI로 요약했습니다.", java.util.List.of(), summary, null));
        history.appendPair(id, "근황 알려줘", new ChatModels.AiResponse("answered", "시세와 비밀 기사 제목", java.util.List.of(), composite, null));
        history.appendPair(id, "현재가", new ChatModels.AiResponse("answered", "시세 조회 결과입니다.", java.util.List.of(), quote, null));

        assertThat(history.scrubExpiredNews(id)).isZero();   // 24시간 전에는 그대로 둔다
        assertThat(history.latestNewsLinks(id)).containsExactly("https://biz.chosun.com/a/1");

        jdbc.update("UPDATE chat_message SET created_at = NOW(6) - INTERVAL 25 HOUR WHERE conversation_id=?", id);
        assertThat(history.scrubExpiredNews(id)).isEqualTo(3);
        assertThat(history.scrubExpiredNews(id)).isZero();   // 한 번 지운 기록은 다시 건드리지 않는다

        String stored = String.join("\n", jdbc.queryForList(
                "SELECT CONCAT(content,'|',context_content,'|',COALESCE(response_json,'')) FROM chat_message WHERE conversation_id=? AND role='assistant'",
                String.class, id));
        assertThat(stored).doesNotContain("비밀 기사 제목").contains(ChatHistory.NEWS_EXPIRED_TEXT).contains("70000");
        var messages = history.messages(user, id);
        assertThat(messages.get(7).text()).isEqualTo("시세 조회 결과입니다.");   // 기사와 무관한 답변은 그대로
        assertThat(messages.get(5).response().presentation().path("data").path("sections").get(0).path("type").asText()).isEqualTo("quote");
        assertThat(history.latestNewsLinks(id)).isEmpty();
    }
}
