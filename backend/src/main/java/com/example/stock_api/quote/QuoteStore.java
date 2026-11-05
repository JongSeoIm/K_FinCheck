package com.example.stock_api.quote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 서버 메모리의 최신 시세 보관소.
 * - Polling 응답과 WebSocket(Push) 전송이 모두 이 값을 읽습니다. (요청마다 KIS 를 호출하지 않습니다.)
 * - 수집할 때마다 (시각, 가격, 누적거래량) 샘플을 쌓아 두고, 초·분봉 집계에 사용합니다.
 */
@Component
public class QuoteStore {

    public static final int MAX_SAMPLES = 30_000;

    /** seq 는 수집 성공마다 1씩 늘어납니다. 같거나 작은 seq 는 중복·역순으로 보고 버립니다. */
    public record Quote(String code, long price, long priceChange, double change, long open, long high,
                        long low, long prevClose, long volume, String updatedAt, long seq) {}

    /** at 은 epoch 밀리초, volume 은 그 시점의 당일 누적거래량. */
    public record Sample(long at, long price, long volume) {}

    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Quote> latest = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Sample>> samples = new ConcurrentHashMap<>();

    /** 수집기(REST)와 실시간 체결(Kafka)이 같은 번호표를 쓰도록 저장소가 seq 를 발급합니다. */
    public long nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 더 새로운 값일 때만 저장합니다.
     * 샘플(초·분봉 집계용)은 sample 이 null 이 아니고, 누적거래량이 직전 샘플과 달라졌을 때만 쌓습니다.
     * (거래가 없던 구간에 같은 값으로 봉이 생기지 않도록 하기 위함입니다.)
     */
    public boolean put(Quote quote, Sample sample) {
        boolean[] accepted = {false};
        latest.compute(quote.code(), (code, old) -> {
            if (old != null && old.seq() >= quote.seq()) {
                return old;
            }
            accepted[0] = true;
            return quote;
        });
        if (accepted[0] && sample != null) {
            ArrayDeque<Sample> deque = samples.computeIfAbsent(quote.code(), c -> new ArrayDeque<>());
            synchronized (deque) {
                if (deque.isEmpty() || deque.peekLast().volume() != sample.volume()) {
                    deque.addLast(sample);
                    while (deque.size() > MAX_SAMPLES) {
                        deque.removeFirst();
                    }
                }
            }
        }
        return accepted[0];
    }

    public Quote get(String code) {
        return latest.get(code);
    }

    public Map<String, Quote> snapshot(Collection<String> codes) {
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String code : codes) {
            Quote quote = latest.get(code);
            if (quote != null) {
                result.put(code, quote);
            }
        }
        return result;
    }

    public List<Sample> samples(String code) {
        ArrayDeque<Sample> deque = samples.get(code);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
