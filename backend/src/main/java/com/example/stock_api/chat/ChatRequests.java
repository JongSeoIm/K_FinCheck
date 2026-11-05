package com.example.stock_api.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 화면에서 받는 채팅 요청. 회원 번호는 요청이 아니라 로그인 정보에서만 가져옵니다. */
public final class ChatRequests {

    static final String CONVERSATION_ID = "[0-9a-fA-F-]{36}";

    private ChatRequests() {}

    public record Stock(@NotBlank @Pattern(regexp = "\\d{6}") String symbol,
                        @NotBlank @Size(max = 100) String name) {}

    public record NewConversation(@NotBlank @Pattern(regexp = "\\d{6}") String symbol) {}

    public record Ask(@NotBlank @Pattern(regexp = CONVERSATION_ID) String conversationId,
                      @NotBlank @Size(max = 1500) String message,
                      @NotNull @Valid Stock stock) {}
}
