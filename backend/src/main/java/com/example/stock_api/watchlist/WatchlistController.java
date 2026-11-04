package com.example.stock_api.watchlist;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.auth.AuthUser;

/**
 * 계정별 관심 종목. 회원 번호는 요청 값이 아니라 로그인 세션에서만 가져오므로,
 * 다른 사람의 목록은 조회·변경할 수 없습니다. 응답은 항상 "현재 목록(종목코드 배열)"입니다.
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistMapper mapper;

    public WatchlistController(WatchlistMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal AuthUser user) {
        if (user == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(mapper.findCodes(user.userId()));
    }

    @PostMapping
    public ResponseEntity<?> add(@AuthenticationPrincipal AuthUser user, @RequestBody Map<String, String> body) {
        if (user == null) {
            return unauthorized();
        }
        String code = body == null ? null : body.get("stockCode");
        if (code == null || !code.matches("\\d{6}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "6자리 종목코드를 입력해 주세요."));
        }
        if (mapper.companyExists(code) == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "지원하지 않는 종목입니다."));
        }
        mapper.insert(user.userId(), code);
        return ResponseEntity.ok(mapper.findCodes(user.userId()));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<?> remove(@AuthenticationPrincipal AuthUser user, @PathVariable String code) {
        if (user == null) {
            return unauthorized();
        }
        if (!code.matches("\\d{6}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "6자리 종목코드를 입력해 주세요."));
        }
        mapper.delete(user.userId(), code);
        return ResponseEntity.ok(mapper.findCodes(user.userId()));
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다."));
    }
}
