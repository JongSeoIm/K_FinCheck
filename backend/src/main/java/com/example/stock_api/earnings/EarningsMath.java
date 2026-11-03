package com.example.stock_api.earnings;

/** 실적 비교 계산 (스프링과 무관한 순수 계산). 누락 값은 0으로 바꾸지 않고 "비교 불가"로 처리합니다. */
final class EarningsMath {

    private EarningsMath() {}

    /** percent 는 UP/DOWN/FLAT 일 때만 값이 있습니다. */
    record Change(Double percent, String status) {}

    /**
     * 상태: UP 증가, DOWN 감소, FLAT 동일, TURN_PROFIT 흑자전환, TURN_LOSS 적자전환,
     *       DEFICIT 적자 지속, NOT_COMPARABLE 비교 불가(값 누락 또는 전기 0)
     */
    static Change change(Long current, Long previous) {
        if (current == null || previous == null || previous == 0L) {
            return new Change(null, "NOT_COMPARABLE");
        }
        if (previous < 0 && current >= 0) {
            return new Change(null, "TURN_PROFIT");
        }
        if (previous > 0 && current < 0) {
            return new Change(null, "TURN_LOSS");
        }
        if (previous < 0) {
            return new Change(null, "DEFICIT");
        }
        double percent = (current - previous) * 100.0 / previous;
        String status = percent > 0 ? "UP" : percent < 0 ? "DOWN" : "FLAT";
        return new Change(percent, status);
    }

    /** "1,234" → 1234. 비어 있거나 "-" 이거나 숫자가 아니면 null (0으로 대체하지 않음). */
    static Long parse(String value) {
        String digits = value == null ? "" : value.replace(",", "").trim();
        if (digits.isEmpty() || "-".equals(digits)) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** DART 접수번호는 14자리 숫자입니다. 그 외 값으로는 링크를 만들지 않습니다. */
    static String dartUrl(String receiptNo) {
        return receiptNo != null && receiptNo.matches("\\d{14}")
                ? "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receiptNo
                : null;
    }
}
