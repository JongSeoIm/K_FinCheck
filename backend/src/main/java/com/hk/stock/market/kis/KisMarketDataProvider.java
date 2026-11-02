package com.hk.stock.market.kis;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.FinancialRowDto;
import com.hk.stock.dtos.OpinionReportDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.market.MarketDataProvider;
import com.hk.stock.market.MarketHours;

/**
 * 한국투자증권 KIS Open API 시세 제공처 (실제 데이터)
 * 사용 API (국내주식 시세)
 *  - 현재가:   /uapi/domestic-stock/v1/quotations/inquire-price               tr_id FHKST01010100
 *  - 당일분봉: /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice tr_id FHKST03010200 (1회 30건)
 *  - 기간별:   /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice tr_id FHKST03010100 (1회 최대 100건)
 *  - 투자의견: /uapi/domestic-stock/v1/quotations/invest-opinion               tr_id FHKST663300C0
 *  - 손익계산서: /uapi/domestic-stock/v1/finance/income-statement               tr_id FHKST66430200
 *  - 재무비율: /uapi/domestic-stock/v1/finance/financial-ratio                 tr_id FHKST66430300
 *  - 실시간:   WebSocket H0STCNT0 (KisRealtimeClient)
 */
public class KisMarketDataProvider implements MarketDataProvider {

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");
	private static final int OPINION_PAGE_SIZE = 100;	//투자의견 1회 최대 건수

	private final KisRestClient rest;
	private final KisRealtimeClient realtime;

	public KisMarketDataProvider(KisRestClient rest, KisRealtimeClient realtime) {
		this.rest = rest;
		this.realtime = realtime;
	}

	@Override
	public String mode() {
		return "KIS";
	}

	@Override
	public QuoteDto fetchQuote(String symbol) {
		JsonNode body = rest.get("/uapi/domestic-stock/v1/quotations/inquire-price", "FHKST01010100", Map.of(
				"FID_COND_MRKT_DIV_CODE", "J",
				"FID_INPUT_ISCD", symbol));
		return KisParsers.quote(symbol, body.path("output"));
	}

	/**
	 * 당일 분봉: 기준 시각부터 과거로 30건씩 받아오므로 장 시작(09:00)까지 반복 호출한다.
	 * 장 마감 후·주말에는 15:30 기준으로 조회하며, 응답에 들어 있는 가장 최근 거래일 데이터만 사용한다.
	 */
	@Override
	public List<ChartPointDto> fetchTodayMinutes(String symbol) {
		ZonedDateTime now = MarketHours.now();
		LocalTime cursor = MarketHours.isOpen(now) ? now.toLocalTime().withNano(0) : MarketHours.CLOSE;
		Map<String, ChartPointDto> rows = new LinkedHashMap<>();
		String tradeDate = null;

		for (int i = 0; i < 15; i++) {
			JsonNode body = rest.get("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice", "FHKST03010200", Map.of(
					"FID_ETC_CLS_CODE", "",
					"FID_COND_MRKT_DIV_CODE", "J",
					"FID_INPUT_ISCD", symbol,
					"FID_INPUT_HOUR_1", cursor.format(HMS),
					"FID_PW_DATA_INCU_YN", "N"));

			LocalTime earliest = null;
			for (JsonNode r : body.path("output2")) {
				if (!KisParsers.hasDate(r)) continue;
				String date = r.path("stck_bsop_date").asText();
				if (tradeDate == null) tradeDate = date;
				if (!tradeDate.equals(date)) continue;	//이전 거래일 데이터는 제외
				ChartPointDto point = KisParsers.minuteRow(r);
				rows.putIfAbsent(point.getTime(), point);
				LocalTime t = LocalTime.parse(r.path("stck_cntg_hour").asText(), HMS);
				if (earliest == null || t.isBefore(earliest)) earliest = t;
			}
			if (earliest == null || !earliest.isAfter(MarketHours.OPEN)) break;
			cursor = earliest.minusMinutes(1);
		}

		List<ChartPointDto> result = new ArrayList<>(rows.values());
		result.sort(Comparator.comparing(ChartPointDto::getTime));
		return result;
	}

	/**
	 * 일봉: 1회 최대 100건이라 기간을 뒤에서부터 나눠서 반복 호출한다. (수정주가 기준)
	 */
	@Override
	public List<ChartPointDto> fetchDaily(String symbol, LocalDate from, LocalDate to) {
		Map<String, ChartPointDto> rows = new LinkedHashMap<>();
		LocalDate end = to;

		for (int i = 0; i < 8 && !end.isBefore(from); i++) {
			JsonNode body = rest.get("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", "FHKST03010100", Map.of(
					"FID_COND_MRKT_DIV_CODE", "J",
					"FID_INPUT_ISCD", symbol,
					"FID_INPUT_DATE_1", from.format(YMD),
					"FID_INPUT_DATE_2", end.format(YMD),
					"FID_PERIOD_DIV_CODE", "D",
					"FID_ORG_ADJ_PRC", "0"));

			LocalDate oldest = null;
			for (JsonNode r : body.path("output2")) {
				if (!KisParsers.hasDate(r)) continue;
				ChartPointDto point = KisParsers.dailyRow(r);
				rows.putIfAbsent(point.getTime(), point);
				LocalDate d = LocalDate.parse(point.getTime());
				if (oldest == null || d.isBefore(oldest)) oldest = d;
			}
			if (oldest == null || !oldest.isAfter(from)) break;
			end = oldest.minusDays(1);
		}

		List<ChartPointDto> result = new ArrayList<>(rows.values());
		result.sort(Comparator.comparing(ChartPointDto::getTime));
		return result;
	}

	/**
	 * 증권사 리포트별 투자의견 (최신순).
	 * 1회 최대 100건이고 연속조회 표시(tr_cont)가 오지 않으므로, 100건이 차면 가장 오래된 날짜 이전으로 기간을 줄여 다시 조회한다.
	 * 경계 날짜는 다음 조회에 다시 포함되므로 날짜·증권사·의견·목표가가 같은 줄은 한 번만 넣는다.
	 */
	@Override
	public List<OpinionReportDto> fetchOpinions(String symbol, LocalDate from, LocalDate to) {
		Map<String, OpinionReportDto> rows = new LinkedHashMap<>();
		LocalDate end = to;

		for (int i = 0; i < 5 && !end.isBefore(from); i++) {
			JsonNode body = rest.get("/uapi/domestic-stock/v1/quotations/invest-opinion", "FHKST663300C0", Map.of(
					"FID_COND_MRKT_DIV_CODE", "J",
					"FID_COND_SCR_DIV_CODE", "16633",
					"FID_INPUT_ISCD", symbol,
					"FID_INPUT_DATE_1", from.format(YMD),
					"FID_INPUT_DATE_2", end.format(YMD)));

			int count = 0;
			LocalDate oldest = null;
			for (JsonNode r : body.path("output")) {
				if (!KisParsers.hasDate(r)) continue;
				count++;
				OpinionReportDto report = KisParsers.opinionRow(r);
				rows.putIfAbsent(report.getDate() + "|" + report.getBroker() + "|" + report.getOpinion() + "|" + report.getTargetPrice(), report);
				LocalDate d = LocalDate.parse(report.getDate());
				if (oldest == null || d.isBefore(oldest)) oldest = d;
			}
			if (count < OPINION_PAGE_SIZE || oldest == null || !oldest.isAfter(from) || oldest.equals(end)) break;
			end = oldest;	//가장 오래된 날의 리포트가 잘렸을 수 있어 그날부터 다시 받는다
		}

		List<OpinionReportDto> result = new ArrayList<>(rows.values());
		result.sort(Comparator.comparing(OpinionReportDto::getDate).reversed());
		return result;
	}

	//분기 손익계산서(매출액·영업이익) + 재무비율(EPS). FID_DIV_CLS_CODE 1 = 분기
	@Override
	public List<FinancialRowDto> fetchQuarterlyFinancials(String symbol) {
		Map<String, String> params = Map.of(
				"FID_DIV_CLS_CODE", "1",
				"fid_cond_mrkt_div_code", "J",
				"fid_input_iscd", symbol);
		JsonNode income = rest.get("/uapi/domestic-stock/v1/finance/income-statement", "FHKST66430200", params);
		JsonNode ratio = rest.get("/uapi/domestic-stock/v1/finance/financial-ratio", "FHKST66430300", params);
		return KisParsers.financialRows(income.path("output"), ratio.path("output"));
	}

	@Override
	public void startRealtime(List<String> symbols, Consumer<QuoteDto> onTick) {
		realtime.start(symbols, onTick);
	}

	@Override
	public void stop() {
		realtime.stop();
	}

	@Override
	public FeedStatus feedStatus() {
		return realtime.status();
	}
}
