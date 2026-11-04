package com.example.stock_api.auth;

import java.io.Serializable;

/** 로그인한 사용자 (세션의 인증 주체). 비밀번호 해시는 담지 않습니다. */
public record AuthUser(long userId, String loginId, String nickname) implements Serializable {}
