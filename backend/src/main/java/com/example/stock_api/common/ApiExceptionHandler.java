package com.example.stock_api.common;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    /** 회원가입·로그인 입력 검사 실패: 첫 번째 문구를 화면에 보여 줍니다 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first != null ? first.getDefaultMessage() : "입력값을 확인해 주세요.";
        return body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청 형식이 올바르지 않습니다.");
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
