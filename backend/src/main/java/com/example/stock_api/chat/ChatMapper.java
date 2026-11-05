package com.example.stock_api.chat;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 대화방·메시지. 대화방 조회와 삭제는 항상 회원 번호를 함께 확인합니다. */
@Mapper
public interface ChatMapper {

    String ROOM = "SELECT conversation_id, stock_code, stock_name, title, updated_at FROM chat_conversation";

    @Insert("INSERT INTO chat_conversation (conversation_id, user_id, stock_code, stock_name)"
            + " VALUES (#{conversationId}, #{userId}, #{stockCode}, #{stockName})")
    int insertConversation(@Param("conversationId") String conversationId, @Param("userId") long userId,
                           @Param("stockCode") String stockCode, @Param("stockName") String stockName);

    @Select(ROOM + " WHERE user_id = #{userId} AND stock_code = #{stockCode} ORDER BY updated_at DESC, conversation_id DESC")
    List<ChatConversation> findConversations(@Param("userId") long userId, @Param("stockCode") String stockCode);

    @Select(ROOM + " WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    ChatConversation findConversation(@Param("userId") long userId, @Param("conversationId") String conversationId);

    @Delete("DELETE FROM chat_conversation WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int deleteConversation(@Param("userId") long userId, @Param("conversationId") String conversationId);

    @Select("SELECT role, content, response_json FROM chat_message WHERE conversation_id = #{conversationId} ORDER BY message_id")
    List<ChatMessage> findMessages(@Param("conversationId") String conversationId);

    /** AI 문맥: 최근 메시지를 오래된 순서로 돌려줍니다 */
    @Select("SELECT role, context_content FROM (SELECT message_id, role, context_content FROM chat_message"
            + " WHERE conversation_id = #{conversationId} ORDER BY message_id DESC LIMIT #{limit}) recent ORDER BY message_id")
    List<ChatMessage> findRecentMessages(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Select("SELECT response_json FROM chat_message WHERE conversation_id = #{conversationId} AND role = 'assistant'"
            + " ORDER BY message_id DESC LIMIT 1")
    String findLastResponse(@Param("conversationId") String conversationId);

    @Insert("INSERT INTO chat_message (conversation_id, role, content, context_content, response_json)"
            + " VALUES (#{conversationId}, #{role}, #{content}, #{contextContent}, #{responseJson})")
    int insertMessage(@Param("conversationId") String conversationId, @Param("role") String role,
                      @Param("content") String content, @Param("contextContent") String contextContent,
                      @Param("responseJson") String responseJson);

    /** 첫 질문을 제목으로 쓰고 마지막 대화 시각을 갱신합니다 */
    @Update("UPDATE chat_conversation SET title = IF(title = '새 대화', #{title}, title), updated_at = CURRENT_TIMESTAMP(6)"
            + " WHERE conversation_id = #{conversationId}")
    int touchConversation(@Param("conversationId") String conversationId, @Param("title") String title);
}
