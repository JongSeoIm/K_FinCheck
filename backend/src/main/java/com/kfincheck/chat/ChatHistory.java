package com.kfincheck.chat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class ChatHistory {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DataSource dataSource;
    public ChatHistory(JdbcTemplate jdbc, ObjectMapper json, DataSource dataSource) {
        this.jdbc = jdbc; this.json = json; this.dataSource = dataSource;
    }
    @PostConstruct void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/chat-history.sql")).execute(dataSource);
    }
    public record Room(String conversationId, String symbol, String name, String title, String updatedAt) { }
    public record Message(String role, String text, ChatModels.AiResponse response) { }
    private final org.springframework.jdbc.core.RowMapper<Room> rooms = (r, n) -> new Room(
        r.getString("conversation_id"), r.getString("stock_code"), r.getString("stock_name"),
        r.getString("title"), r.getString("updated_at"));
    public List<Room> list(long user, String symbol) {
        return jdbc.query("SELECT * FROM chat_conversation WHERE user_id=? AND stock_code=? ORDER BY updated_at DESC, conversation_id DESC", rooms, user, symbol);
    }
    public Room owned(long user, String id, boolean lock) {
        var result = jdbc.query("SELECT * FROM chat_conversation WHERE user_id=? AND conversation_id=?" + (lock ? " FOR UPDATE" : ""), rooms, user, id);
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다.");
        return result.getFirst();
    }
    public Room create(long user, ChatModels.Stock stock) {
        if (stock == null || stock.symbol() == null || !stock.symbol().matches("\\d{6}") || stock.name() == null || stock.name().isBlank() || stock.name().length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "종목 정보를 확인해 주세요.");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_conversation(conversation_id,user_id,stock_code,stock_name) VALUES(?,?,?,?)", id, user, stock.symbol(), stock.name());
        return owned(user, id, false);
    }
    public List<Message> messages(long user, String id) {
        owned(user, id, false);
        scrubExpiredNews(id);
        return jdbc.query("SELECT role,content,response_json FROM chat_message WHERE conversation_id=? ORDER BY message_id", (r,n) -> {
            try { return new Message(r.getString(1), r.getString(2), r.getString(3) == null ? null : json.readValue(r.getString(3), ChatModels.AiResponse.class)); }
            catch (Exception error) { throw new IllegalStateException("저장된 대화를 읽지 못했습니다."); }
        }, id);
    }
    public List<ChatModels.ContextMessage> context(String id) {
        scrubExpiredNews(id);
        var result = jdbc.query("SELECT role,context_content FROM chat_message WHERE conversation_id=? ORDER BY message_id DESC LIMIT 10",
            (r,n) -> new ChatModels.ContextMessage(r.getString(1), r.getString(2)), id);
        java.util.Collections.reverse(result);
        return result;
    }
    static final String NEWS_EXPIRED_TEXT = "기사 관련 답변은 보관 기간(24시간)이 지나 삭제되었습니다.";
    static final String COMPOSITE_EXPIRED_TEXT = "시세·실적 자료는 아래에 표시됩니다. 기사 관련 내용은 보관 기간(24시간)이 지나 삭제되었습니다.";

    /**
     * 24시간이 지난 답변에서 기사 제목·요약 등 기사 관련 내용을 지운다(조선비즈 '보관용 사본 금지' 대응).
     * 대상: 뉴스 목록(news), 기사 요약(news_summary), 뉴스가 들어간 종합 답변(composite)의 뉴스 부분과 답변 문장.
     * conversationId가 null이면 전체 대화를 정리한다.
     */
    public int scrubExpiredNews(String conversationId) {
        String sql = "SELECT message_id,response_json FROM chat_message WHERE role='assistant' AND created_at < NOW(6) - INTERVAL 24 HOUR"
                + " AND response_json LIKE '%\"type\":\"news%' AND response_json NOT LIKE '%\"newsExpired\":true%'"
                + (conversationId == null ? "" : " AND conversation_id=?");
        Object[] args = conversationId == null ? new Object[0] : new Object[]{conversationId};
        int changed = 0;
        for (var row : jdbc.queryForList(sql, args)) {
            try {
                var response = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree((String) row.get("response_json"));
                var presentation = response.path("presentation");
                if (!presentation.isObject()) continue;
                String type = presentation.path("type").asText();
                String text;
                if ("composite".equals(type)) {
                    var sections = presentation.path("data").path("sections");
                    if (!sections.isArray()) continue;
                    var array = (com.fasterxml.jackson.databind.node.ArrayNode) sections;
                    for (int i = 0; i < array.size(); i++)
                        if ("news".equals(array.get(i).path("type").asText())) array.set(i, expiredNews("news"));
                    ((com.fasterxml.jackson.databind.node.ObjectNode) presentation.path("data")).put("newsExpired", true);
                    text = COMPOSITE_EXPIRED_TEXT;
                } else if ("news".equals(type) || "news_summary".equals(type)) {
                    response.set("presentation", expiredNews(type));
                    text = NEWS_EXPIRED_TEXT;
                } else continue;
                response.put("answer", text);
                jdbc.update("UPDATE chat_message SET content=?,context_content=?,response_json=? WHERE message_id=?",
                        text, text, json.writeValueAsString(response), row.get("message_id"));
                changed++;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* 읽을 수 없는 기록은 건너뛴다. */ }
        }
        return changed;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode expiredNews(String type) {
        var node = json.createObjectNode().put("type", type);
        node.putObject("data").put("newsExpired", true).put("expired", true);
        return node;
    }

    /** 이 대화에서 가장 최근에 보여 준 기사 목록의 링크(화면 번호 순서). "2번 기사 요약해줘"에 쓴다. */
    public List<String> latestNewsLinks(String id) {
        scrubExpiredNews(id);
        var rows = jdbc.queryForList("SELECT response_json FROM chat_message WHERE conversation_id=? AND role='assistant' AND response_json IS NOT NULL ORDER BY message_id DESC LIMIT 20", String.class, id);
        for (String row : rows) {
            try {
                var presentation = json.readTree(row).path("presentation");
                var news = "news".equals(presentation.path("type").asText()) ? presentation : null;
                if ("composite".equals(presentation.path("type").asText()))
                    for (var section : presentation.path("data").path("sections"))
                        if ("news".equals(section.path("type").asText())) news = section;
                if (news == null) continue;
                List<String> links = new java.util.ArrayList<>();
                for (var article : news.path("data").path("articles")) {
                    String link = article.path("link").asText("");
                    if (link.startsWith("https://") || link.startsWith("http://")) links.add(link);
                }
                if (!links.isEmpty()) return links;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* 읽을 수 없는 기록은 건너뛴다. */ }
        }
        return List.of();
    }
    public void appendPair(String id, String question, ChatModels.AiResponse response) {
        try {
            jdbc.update("INSERT INTO chat_message(conversation_id,role,content,context_content) VALUES(?,'user',?,?)", id, question,
                response.contextMessage() == null ? question : response.contextMessage());
            jdbc.update("INSERT INTO chat_message(conversation_id,role,content,context_content,response_json) VALUES(?,'assistant',?,?,?)", id, response.answer(), response.answer(), json.writeValueAsString(response));
            jdbc.update("UPDATE chat_conversation SET title=IF(title='새 대화',?,title),updated_at=CURRENT_TIMESTAMP(6) WHERE conversation_id=?", question.substring(0, Math.min(80, question.length())), id);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("대화 저장에 실패했습니다."); }
    }
    @Transactional public void remove(long user, String id) {
        owned(user, id, true);
        jdbc.update("DELETE FROM chat_conversation WHERE user_id=? AND conversation_id=?", user, id);
    }
}
