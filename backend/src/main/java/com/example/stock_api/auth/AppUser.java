package com.example.stock_api.auth;

import lombok.Getter;
import lombok.Setter;

/** app_user 한 행. passwordHash 는 서버 안에서만 쓰고 응답으로 내보내지 않습니다. */
@Getter
@Setter
public class AppUser {
    private Long userId;
    private String loginId;
    private String passwordHash;
    private String nickname;
}
