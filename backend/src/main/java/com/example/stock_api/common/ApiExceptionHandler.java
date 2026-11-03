package com.example.stock_api.common;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/** 모든 오류 응답을 {"code": "...", "error": "..."} 한 가지 형태로 맞춥니다. */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_CODE", "종목코드는 숫자 6자리입니다. (예: 005930)");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> db(DataAccessException e) {
        log.warn("DB 오류: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "DB_UNAVAILABLE", "DB 에 연결할 수 없습니다.");
    }

    @ExceptionHandler({RestClientException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> upstream(Exception e) {
        log.warn("외부 API 오류: {}", e.getMessage());
        return body(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", String.valueOf(e.getMessage()));
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "error", message));
    }
}
