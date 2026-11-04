package com.example.stock_api.realtime;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.stock_api.realtime.RealtimeMessageParser.Tick;

/**
 * 실시간 체결을 초·분봉으로 집계합니다. 체결이 없는 구간은 봉을 만들지 않습니다.
 * 서버 메모리에 간격별 최근 봉만 보관하므로, 서버를 켠 뒤 받은 체결만 봉에 들어갑니다.
 */
public class BarAggregator {

    /** 지원 간격(초): 1초·5초·30초·1분·3분·5분·10분·15분·30분·60분 */
    public static final List<Integer> INTERVALS = List.of(1, 5, 30, 60, 180, 300, 600, 900, 1800, 3600);

    private static final int MAX_BARS = 300;

    public record Bar(int interval, String time, long open, long high, long low, long close, long volume) {}

    private static final class CodeState {
        private LocalDate date;
        private long lastVolume;
        private final Map<Integer, Deque<Bar>> bars = new HashMap<>();
    }

    private final Map<String, CodeState> states = new HashMap<>();

    /** 체결을 모든 간격에 반영하고, 바뀐 봉을 돌려줍니다. */
    public synchronized List<Bar> apply(Tick tick, LocalDate today) {
        CodeState state = states.computeIfAbsent(tick.code(), code -> new CodeState());
        if (!today.equals(state.date)) {
            state.date = today;
            state.lastVolume = 0;
            state.bars.clear();
        }
        long volume = state.lastVolume > 0 ? Math.max(0, tick.volume() - state.lastVolume) : 0;
        if (tick.volume() > 0) state.lastVolume = tick.volume();

        int second = secondOfDay(tick.time());
        List<Bar> updated = new ArrayList<>();
        for (int interval : INTERVALS) {
            Deque<Bar> bars = state.bars.computeIfAbsent(interval, key -> new ArrayDeque<>());
            String time = timeText(second - second % interval);
            Bar last = bars.peekLast();
            Bar bar;
            if (last != null && last.time().equals(time)) {
                bars.pollLast();
                bar = new Bar(interval, time, last.open(), Math.max(last.high(), tick.price()),
                        Math.min(last.low(), tick.price()), tick.price(), last.volume() + volume);
            } else {
                bar = new Bar(interval, time, tick.price(), tick.price(), tick.price(), tick.price(), volume);
            }
            bars.addLast(bar);
            if (bars.size() > MAX_BARS) bars.pollFirst();
            updated.add(bar);
        }
        return updated;
    }

    public synchronized List<Bar> bars(String code, int interval) {
        CodeState state = states.get(code);
        if (state == null || !state.bars.containsKey(interval)) return List.of();
        return List.copyOf(state.bars.get(interval));
    }

    private static int secondOfDay(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 3600
                + Integer.parseInt(time.substring(2, 4)) * 60
                + Integer.parseInt(time.substring(4, 6));
    }

    private static String timeText(int second) {
        return "%02d:%02d:%02d".formatted(second / 3600, second % 3600 / 60, second % 60);
    }
}