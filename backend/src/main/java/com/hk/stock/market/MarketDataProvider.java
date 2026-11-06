package com.hk.stock.market;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import com.hk.stock.dtos.ChartPointDto;
import com.hk.stock.dtos.FinancialRowDto;
import com.hk.stock.dtos.OpinionReportDto;
import com.hk.stock.dtos.QuoteDto;

/**
 * 시세 제공처 공통 규격.
 * 실제 데이터(KisMarketDataProvider)와 모의 데이터(DemoMarketDataProvider)가 이 규격을 따른다.
 * 실시간 체결은 startRealtime 의 콜백으로 받아 Kafka 로 발행한다.
 */
public interface MarketDataProvider {

	//KIS(실제) / DEMO(모의)
	String mode();

	//현재가 1건 조회 (REST)
	QuoteDto fetchQuote(String symbol);

	//가장 최근 거래일의 1분봉 (오래된 순)
	List<ChartPointDto> fetchTodayMinutes(String symbol);

	//일봉 (오래된 순)
	List<ChartPointDto> fetchDaily(String symbol, LocalDate from, LocalDate to);

	//주봉 W · 월봉 M · 연봉 Y (오래된 순)
	List<ChartPointDto> fetchPeriod(String symbol, String period, LocalDate from, LocalDate to);

	//증권사 리포트별 투자의견 (최신순, category 는 비워 두고 OpinionCalculator 가 채운다)
	List<OpinionReportDto> fetchOpinions(String symbol, LocalDate from, LocalDate to);

	//분기 재무 (그해 누적값, 최신순)
	List<FinancialRowDto> fetchQuarterlyFinancials(String symbol);

	//실시간 체결 수신 시작. 체결이 올 때마다 onTick 호출
	void startRealtime(List<String> symbols, Consumer<QuoteDto> onTick);

	void stop();

	FeedStatus feedStatus();

	enum FeedStatus { CONNECTING, CONNECTED, DISCONNECTED, DEMO }
}
