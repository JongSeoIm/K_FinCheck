package com.kfincheck.database;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class DatabaseHealthController {
    private final DatabaseCheckMapper mapper;

    public DatabaseHealthController(DatabaseCheckMapper mapper) { this.mapper = mapper; }

    @GetMapping("/api/health/db")
    public ResponseEntity<Map<String, Object>> check() {
        try {
            if (mapper.ping() == 1 && "k_fincheck".equals(mapper.currentDatabase())) {
                return ResponseEntity.ok(Map.of("status", "UP", "database", "k_fincheck", "queryResult", 1));
            }
        } catch (RuntimeException ignored) {
            // JDBC 예외·접속 정보·인증 정보는 응답에 전달하지 않는다.
        }
        return ResponseEntity.status(503).body(Map.of("status", "DOWN",
                "error", "프로젝트 DB 연결을 확인해 주세요."));
    }
}
