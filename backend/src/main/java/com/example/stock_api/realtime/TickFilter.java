package com.example.stock_api.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 오래된 체결, 같거나 줄어든 누적거래량(중복·역순)을 걸러냅니다. 종목별로 직전 체결만 기억합니다. */
public class TickFilter {

    private final Map<String, QuoteEvent> last = new ConcurrentHashMap<>();

    /** 새 체결이면 true 를 돌려주고 기준을 갱신합니다. */
    public boolean accept(QuoteEvent event) {
        boolean[] ok = {false};
        last.compute(event.stockCode(), (code, prev) -> {
            if (prev == null || isNewer(prev, event)) {
                ok[0] = true;
                return event;
            }
            return prev;
        });
        return ok[0];
    }

    private static boolean isNewer(QuoteEvent prev, QuoteEvent event) {
        if (event.exchangeTime().isBefore(prev.exchangeTime())) {
            return false;
        }
        // 같은 날에는 누적거래량이 늘어야 새 체결입니다. 날짜가 바뀌면 누적거래량이 다시 시작합니다.
        if (event.businessDate().equals(prev.businessDate())) {
            return event.volume() > prev.volume();
        }
        return true;
    }
}
