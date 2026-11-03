package com.example.stock_api.common;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 정규장(평일 09:00~15:30, KST) 판단. 공휴일은 반영하지 않습니다. */
public final class MarketHours {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    private MarketHours() {}

    public static boolean isOpen() {
        return isOpen(ZonedDateTime.now(KST));
    }

    public static boolean isOpen(ZonedDateTime at) {
        ZonedDateTime kst = at.withZoneSameInstant(KST);
        DayOfWeek d = kst.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false;
        LocalTime t = kst.toLocalTime();
        return !t.isBefore(OPEN) && !t.isAfter(CLOSE);
    }
}
