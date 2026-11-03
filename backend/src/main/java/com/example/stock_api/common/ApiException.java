package com.example.stock_api.common;

import org.springframework.http.HttpStatus;

/** 화면에 그대로 보여 줄 수 있는 오류. 응답은 {"code": ..., "error": ...} 형태입니다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notSupported(String stockCode) {
        return new ApiException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND",
                "지원하지 않는 종목입니다: " + stockCode);
    }
}