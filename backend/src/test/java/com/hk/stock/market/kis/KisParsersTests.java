package com.hk.stock.market.kis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.FinancialRowDto;
import com.hk.stock.dtos.OpinionReportDto;
import com.hk.stock.dtos.QuoteDto;

//KIS 응답 변환 규칙 확인 (DB·네트워크 없이 실행)
class KisParsersTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void 하락_부호는_음수로_변환한다() throws Exception {
		String json = """
				{"stck_prpr":"70500","prdy_vrss":"-500","prdy_vrss_sign":"5","prdy_ctrt":"-0.70",
				 "stck_oprc":"71000","stck_hgpr":"71200","stck_lwpr":"70300","stck_sdpr":"71000","acml_vol":"1234567"}
				""";
		QuoteDto q = KisParsers.quote("005930", objectMapper.readTree(json));
		assertEquals(new BigDecimal("70500"), q.getPrice());
		assertEquals(new BigDecimal("-500"), q.getChangeAmount());
		assertEquals(new BigDecimal("-0.70"), q.getChangeRate());
		assertEquals(new BigDecimal("71000"), q.getPreviousClose());
		assertEquals(1234567L, q.getCumulativeVolume());
		assertNull(q.getEventTime());	//현재가 응답에는 체결 시각이 없다. 조회 시각을 체결 시각으로 쓰지 않는다
	}

	@Test
	void 분봉에서_가장_최근_체결_시각을_찾는다() throws Exception {
		String json = """
				[{"stck_bsop_date":"20261104","stck_cntg_hour":"152900"},
				 {"stck_bsop_date":"20261104","stck_cntg_hour":"153000"},
				 {"stck_bsop_date":"20261103","stck_cntg_hour":"153000"},
				 {}]
				""";
		assertEquals("2026-11-04T15:30+09:00", KisParsers.latestTradeTime(objectMapper.readTree(json)));
		assertNull(KisParsers.latestTradeTime(objectMapper.readTree("[]")));
	}

	@Test
	void 실시간_체결_문자열을_변환한다() {
		String data = "005930^093001^71500^2^500^0.70^71200^71000^71600^70900^71600^71500^10^2500000";
		QuoteDto q = KisParsers.realtimeTick(data.split("\\^"), 0);
		assertEquals("005930", q.getSymbol());
		assertEquals(new BigDecimal("71500"), q.getPrice());
		assertEquals(new BigDecimal("500"), q.getChangeAmount());
		assertEquals(new BigDecimal("71000"), q.getPreviousClose());
		assertEquals(2500000L, q.getCumulativeVolume());
	}

	@Test
	void 일봉_한_줄을_변환한다() throws Exception {
		String json = """
				{"stck_bsop_date":"20261030","stck_oprc":"71000","stck_hgpr":"71600","stck_lwpr":"70900","stck_clpr":"71500","acml_vol":"9000000"}
				""";
		ChartPointDto p = KisParsers.dailyRow(objectMapper.readTree(json));
		assertEquals("2026-10-30", p.getTime());
		assertEquals(new BigDecimal("71500"), p.getClose());
	}

	@Test
	void 재무_응답의_99_99는_값_없음으로_보고_EPS를_년월로_합친다() throws Exception {
		String income = """
				[{"stac_yymm":"202603","sale_account":"1338734.00","bsop_prti":"99.99"},
				 {"stac_yymm":"202606","sale_account":"3053729.00","bsop_prti":"1467252.00"}]
				""";
		String ratio = """
				[{"stac_yymm":"202606","eps":"17687.00"}]
				""";
		List<FinancialRowDto> rows = KisParsers.financialRows(objectMapper.readTree(income), objectMapper.readTree(ratio));
		assertEquals("202606", rows.get(0).getYearMonth());	//최신순
		assertEquals(new BigDecimal("17687.00"), rows.get(0).getEps());
		assertNull(rows.get(1).getOperatingProfit());
		assertNull(rows.get(1).getEps());
	}

	@Test
	void 투자의견_한_줄을_변환하고_목표가_0은_없음으로_본다() throws Exception {
		String json = """
				{"stck_bsop_date":"20261030","invt_opnn":"BUY","mbcr_name":" KB ","hts_goal_prc":"0"}
				""";
		OpinionReportDto r = KisParsers.opinionRow(objectMapper.readTree(json));
		assertEquals("2026-10-30", r.getDate());
		assertEquals("KB", r.getBroker());
		assertNull(r.getTargetPrice());
	}
}
