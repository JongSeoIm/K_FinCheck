package com.example.stock_api.quote;

import java.util.List;

/** 서버가 시세를 계속 수집하는 지원 종목. (company 테이블 기반으로 넓힐 때 이 목록만 바꾸면 됩니다.) */
public final class SupportedStocks {

    public static final List<String> CODES = List.of("005930", "000660", "035420");

    private SupportedStocks() {}

    public static boolean contains(String code) {
        return CODES.contains(code);
    }
}
