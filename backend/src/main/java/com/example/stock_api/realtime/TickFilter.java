package com.example.stock_api.realtime;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.stock_api.realtime.RealtimeMessageParser.Tick;

/**
 * 같은 체결이 다시 오거나 순서가 뒤집혀 오면 걸러냅니다.
 * 체결 시각(HHmmss)과 누적거래량을 종목별 마지막 값과 비교하며, 날짜가 바뀌면 기준을 초기화합니다.
 */
public class TickFilter {

    private record Last(LocalDate date, String time, long volume) {}

    private final Map<String, Last> lastByCode = new ConcurrentHashMap<>();

    public boolean accept(Tick tick, LocalDate today) {
        boolean[] accepted = {true};
        lastByCode.compute(tick.code(), (code, last) -> {
            if (last != null && last.date().equals(today) && isOldOrDuplicate(tick, last)) {
                accepted[0] = false;
                return last;
            }
            return new Last(today, tick.time(), tick.volume());
        });
        return accepted[0];
    }

    private static boolean isOldOrDuplicate(Tick tick, Last last) {
        int order = tick.time().compareTo(last.time());
        if (order < 0) return true;
        // 같은 초에는 누적거래량이 늘어난 체결만 새 체결입니다
        return order == 0 && tick.volume() <= last.volume();
    }
}