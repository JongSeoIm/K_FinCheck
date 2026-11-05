package com.example.stock_api.chat;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** chat_conversation 한 행 */
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation {
    private String conversationId;
    private String stockCode;
    private String stockName;
    private String title;
    private LocalDateTime updatedAt;
}
