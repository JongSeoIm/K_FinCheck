package com.example.stock_api.chat;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** chat_message 한 행 */
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {
    private String role;
    private String content;
    private String contextContent;
    private String responseJson;
}
