package com.hk.stock.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.hk.stock.dtos.ChartPointDto;

//1분봉을 더 긴 분봉으로 묶고, 저장·조회한 봉과 수신 중인 봉을 시간 기준으로 합친다
public final class CandleRollup {

	private CandleRollup() {}

	public static long epoch(ChartPointDto point) {
		return OffsetDateTime.parse(point.getTime()).toEpochSecond();
	}

	//points 는 오래된 순. 체결이 없는 구간은 만들지 않는다
	public static List<ChartPointDto> rollup(List<ChartPointDto> points, int seconds) {
		TreeMap<Long, ChartPointDto> bars = new TreeMap<>();
		for (ChartPointDto p : points) {
			long time = epoch(p);
			long start = time - Math.floorMod(time, (long) seconds);
			ChartPointDto bar = bars.get(start);
			if (bar == null) {
				bars.put(start, new ChartPointDto(QuoteStore.isoTime(start), p.getOpen(), p.getHigh(), p.getLow(), p.getClose(), p.getVolume(), p.getComplete()));
			} else {
				bar.setHigh(bar.getHigh().max(p.getHigh()));
				bar.setLow(bar.getLow().min(p.getLow()));
				bar.setClose(p.getClose());
				bar.setVolume(sum(bar.getVolume(), p.getVolume()));
				bar.setComplete(p.getComplete());
			}
		}
		return new ArrayList<>(bars.values());
	}

	//뒤에 오는 목록(live)이 더 최신이다. 같은 구간이면 저장된 봉의 시가를 유지하고 고가·저가·종가를 합친다
	public static List<ChartPointDto> merge(List<ChartPointDto> base, List<ChartPointDto> live) {
		Map<Long, ChartPointDto> bars = new TreeMap<>();
		for (ChartPointDto p : base) bars.put(epoch(p), p);
		for (ChartPointDto p : live) {
			long time = epoch(p);
			ChartPointDto old = bars.get(time);
			if (old == null) {
				bars.put(time, p);
			} else {
				BigDecimal high = old.getHigh().max(p.getHigh());
				BigDecimal low = old.getLow().min(p.getLow());
				Long volume = old.getVolume() == null ? p.getVolume()
						: p.getVolume() == null ? old.getVolume() : Long.valueOf(Math.max(old.getVolume(), p.getVolume()));
				bars.put(time, new ChartPointDto(p.getTime(), old.getOpen(), high, low, p.getClose(), volume, p.getComplete()));
			}
		}
		return new ArrayList<>(bars.values());
	}

	private static Long sum(Long a, Long b) {
		if (a == null) return b;
		return b == null ? a : Long.valueOf(a + b);
	}
}
