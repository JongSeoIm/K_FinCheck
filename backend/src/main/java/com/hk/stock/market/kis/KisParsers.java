package com.hk.stock.market.kis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.FinancialRowDto;
import com.hk.stock.dtos.OpinionReportDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.market.MarketHours;

/**
 * KIS 응답 → 우리 공통 형식 변환
 * 전일 대비 부호(prdy_vrss_sign): 1 상한, 2 상승, 3 보합, 4 하한, 5 하락
 */
public final class KisParsers {

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");
	private static final BigDecimal MISSING = new BigDecimal("99.99");	//KIS 재무 응답의 "값 없음" 표시

	private KisParsers() {}

	//주식현재가 시세(FHKST01010100) output
	public static QuoteDto quote(String symbol, JsonNode o) {
		String sign = o.path("prdy_vrss_sign").asText();
		return QuoteDto.builder()
				.symbol(symbol)
				.price(decimal(o.path("stck_prpr").asText()))
				.change(signed(o.path("prdy_vrss").asText(), sign))
				.changeRate(signed(o.path("prdy_ctrt").asText(), sign))
				.open(decimal(o.path("stck_oprc").asText()))
				.high(decimal(o.path("stck_hgpr").asText()))
				.low(decimal(o.path("stck_lwpr").asText()))
				.previousClose(decimal(o.path("stck_sdpr").asText()))
				.volume(toLong(o.path("acml_vol").asText()))
				.tradeTime(MarketHours.now().withNano(0).toOffsetDateTime().toString())
				.source("KIS")
				.build();
	}

	//실시간 체결가(H0STCNT0) 필드 순서: 0 종목코드, 1 체결시각, 2 현재가, 3 부호, 4 전일대비, 5 등락률,
	//6 가중평균가, 7 시가, 8 고가, 9 저가, 10 매도호가, 11 매수호가, 12 체결량, 13 누적거래량 ...
	public static QuoteDto realtimeTick(String[] f, int base) {
		String sign = f[base + 3];
		BigDecimal price = decimal(f[base + 2]);
		BigDecimal change = signed(f[base + 4], sign);
		LocalTime time = LocalTime.parse(f[base + 1], HMS);
		return QuoteDto.builder()
				.symbol(f[base])
				.price(price)
				.change(change)
				.changeRate(signed(f[base + 5], sign))
				.open(decimal(f[base + 7]))
				.high(decimal(f[base + 8]))
				.low(decimal(f[base + 9]))
				.previousClose(price != null && change != null ? price.subtract(change) : null)
				.volume(toLong(f[base + 13]))
				.tradeTime(ZonedDateTime.of(MarketHours.now().toLocalDate(), time, MarketHours.KST).toOffsetDateTime().toString())
				.source("KIS")
				.build();
	}

	//일봉(FHKST03010100) output2 한 줄
	public static ChartPointDto dailyRow(JsonNode r) {
		LocalDate date = LocalDate.parse(r.path("stck_bsop_date").asText(), YMD);
		return new ChartPointDto(
				date.toString(),
				decimal(r.path("stck_oprc").asText()),
				decimal(r.path("stck_hgpr").asText()),
				decimal(r.path("stck_lwpr").asText()),
				decimal(r.path("stck_clpr").asText()),
				toLong(r.path("acml_vol").asText()));
	}

	//당일 분봉(FHKST03010200) output2 한 줄
	public static ChartPointDto minuteRow(JsonNode r) {
		LocalDate date = LocalDate.parse(r.path("stck_bsop_date").asText(), YMD);
		LocalTime time = LocalTime.parse(r.path("stck_cntg_hour").asText(), HMS);
		return new ChartPointDto(
				ZonedDateTime.of(date, time, MarketHours.KST).toOffsetDateTime().toString(),
				decimal(r.path("stck_oprc").asText()),
				decimal(r.path("stck_hgpr").asText()),
				decimal(r.path("stck_lwpr").asText()),
				decimal(r.path("stck_prpr").asText()),
				toLong(r.path("cntg_vol").asText()));
	}

	//종목투자의견(FHKST663300C0) output 한 줄
	public static OpinionReportDto opinionRow(JsonNode r) {
		BigDecimal target = decimal(r.path("hts_goal_prc").asText());
		return OpinionReportDto.builder()
				.date(LocalDate.parse(r.path("stck_bsop_date").asText(), YMD).toString())
				.broker(r.path("mbcr_name").asText("").trim())
				.opinion(r.path("invt_opnn").asText("").trim())
				.targetPrice(target == null || target.signum() <= 0 ? null : target)
				.build();
	}

	/**
	 * 손익계산서(FHKST66430200)와 재무비율(FHKST66430300) 분기 응답을 결산 년월로 합친다 (최신순).
	 * KIS는 값이 없는 항목을 99.99 로 보내므로 null 로 바꾼다 (0으로 두지 않는다).
	 */
	public static List<FinancialRowDto> financialRows(JsonNode income, JsonNode ratio) {
		Map<String, BigDecimal> epsByMonth = new HashMap<>();
		for (JsonNode r : ratio) {
			epsByMonth.put(r.path("stac_yymm").asText(), amount(r.path("eps").asText()));
		}
		List<FinancialRowDto> rows = new ArrayList<>();
		for (JsonNode r : income) {
			String yearMonth = r.path("stac_yymm").asText();
			if (yearMonth.length() != 6) continue;
			rows.add(new FinancialRowDto(yearMonth,
					amount(r.path("sale_account").asText()),
					amount(r.path("bsop_prti").asText()),
					epsByMonth.get(yearMonth)));
		}
		rows.sort(Comparator.comparing(FinancialRowDto::getYearMonth).reversed());
		return rows;
	}

	static BigDecimal amount(String value) {
		BigDecimal v = decimal(value);
		return v == null || v.compareTo(MISSING) == 0 ? null : v;
	}

	public static boolean hasDate(JsonNode r) {
		String date = r.path("stck_bsop_date").asText();
		return date != null && date.length() == 8;
	}

	//부호 코드로 값의 부호를 맞춘다 (응답 값에 부호가 이미 붙어 있어도 안전)
	static BigDecimal signed(String value, String sign) {
		BigDecimal v = decimal(value);
		if (v == null) return null;
		v = v.abs();
		if ("4".equals(sign) || "5".equals(sign)) return v.negate();
		if ("3".equals(sign)) return BigDecimal.ZERO;
		return v;
	}

	static BigDecimal decimal(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return new BigDecimal(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static Long toLong(String value) {
		BigDecimal v = decimal(value);
		return v == null ? null : v.longValue();
	}

	static int toInt(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception e) {
			return fallback;
		}
	}
}
