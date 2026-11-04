package com.example.stock_api.auth;

import java.io.Serializable;

/** 로그인한 사용자. 세션에 저장되고 화면에도 그대로 내려갑니다 (비밀번호 없음). */
public record LoginUser(long userId, String loginId, String nickname) implements Serializable {

    /** 세션에 저장할 때 쓰는 이름 */
    public static final String SESSION_KEY = "loginUser";
}
