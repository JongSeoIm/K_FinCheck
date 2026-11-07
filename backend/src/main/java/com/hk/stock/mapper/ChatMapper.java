package com.hk.stock.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.hk.stock.dtos.ChatConversationDto;
import com.hk.stock.dtos.ChatMessageRowDto;

@Mapper
public interface ChatMapper {
	//본인 대화방만 찾는다 (다른 회원의 대화방이면 null)
	public ChatConversationDto getConversation(@Param("userId") Long userId, @Param("conversationId") String conversationId);
	//최근에 이어간 순서로 limit 개
	public List<ChatConversationDto> getConversations(@Param("userId") Long userId, @Param("stockCode") String stockCode, @Param("limit") int limit);
	public int insertConversation(@Param("conversationId") String conversationId, @Param("userId") Long userId,
			@Param("stockCode") String stockCode, @Param("stockName") String stockName);
	public int touchConversation(@Param("conversationId") String conversationId, @Param("title") String title);
	public int insertMessage(ChatMessageRowDto message);
	//대화방의 최근 메시지 limit 개 (오래된 순)
	public List<ChatMessageRowDto> getRecentMessages(@Param("conversationId") String conversationId, @Param("limit") int limit);
	public int deleteConversation(@Param("userId") Long userId, @Param("conversationId") String conversationId);
}
