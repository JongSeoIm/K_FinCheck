package com.example.stock_api.stock;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** 지원 종목 조회. 목록은 1분 동안 메모리에 두고 재사용합니다 (DB 에 행을 추가하면 1분 안에 반영). */
@Service
public class StockService {

    private static final long LIST_TTL_MS = 60_000;

    private final StockMapper mapper;

    public StockService(StockMapper mapper) {
        this.mapper = mapper;
    }

    private volatile List<Stock> cached = List.of();
    private volatile long cachedAt = 0;

    public List<Stock> all() {
        if (System.currentTimeMillis() - cachedAt > LIST_TTL_MS || cached.isEmpty()) {
            cached = List.copyOf(mapper.findAll());
            cachedAt = System.currentTimeMillis();
        }
        return cached;
    }

    /** DB 오류가 나도 실시간 처리를 멈추지 않도록 마지막 목록을 돌려줍니다. */
    public List<Stock> allOrCached() {
        try {
            return all();
        } catch (RuntimeException e) {
            return cached;
        }
    }

    public List<String> codes() {
        return allOrCached().stream().map(Stock::getCode).toList();
    }

    public Optional<Stock> find(String code) {
        return Optional.ofNullable(mapper.findByCode(code));
    }

    public List<Stock> search(String q) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) return List.of();
        return mapper.search(keyword);
    }

    public boolean dbAlive() {
        try {
            return mapper.ping() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}