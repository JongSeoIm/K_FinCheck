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
        return jdbc.query("SELECT role,content,response_json FROM chat_message WHERE conversation_id=? ORDER BY message_id", (r,n) -> {
            try { return new Message(r.getString(1), r.getString(2), r.getString(3) == null ? null : json.readValue(r.getString(3), ChatModels.AiResponse.class)); }
            catch (Exception error) { throw new IllegalStateException("저장된 대화를 읽지 못했습니다."); }
        }, id);
    }
    public List<ChatModels.ContextMessage> context(String id) {
        var result = jdbc.query("SELECT role,context_content FROM chat_message WHERE conversation_id=? ORDER BY message_id DESC LIMIT 10",
            (r,n) -> new ChatModels.ContextMessage(r.getString(1), r.getString(2)), id);
        java.util.Collections.reverse(result);
        return result;
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
