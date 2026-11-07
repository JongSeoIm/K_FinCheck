package com.hk.stock.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hk.stock.dtos.ChartDto;
import com.hk.stock.dtos.EarningsDto;
import com.hk.stock.dtos.MarketStatusDto;
import com.hk.stock.dtos.NewsDto;
import com.hk.stock.dtos.OpinionDto;
import com.hk.stock.dtos.QuoteDto;
import com.hk.stock.dtos.StockDto;
import com.hk.stock.market.MarketDataException;
import com.hk.stock.service.ChartService;
import com.hk.stock.service.EarningsService;
import com.hk.stock.service.NewsService;
import com.hk.stock.service.OpinionService;
import com.hk.stock.service.QuoteStore;
import com.hk.stock.service.StockService;

import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api")
@Validated
public class StockController {

	private static final String SYMBOL_REGEX = "\\d{6}";
	private static final String SYMBOL_MESSAGE = "종목코드는 6자리 숫자입니다.";

	@Autowired
	private StockService stockService;
	@Autowired
	private QuoteStore quoteStore;
	@Autowired
	private ChartService chartService;
	@Autowired
	private NewsService newsService;
	@Autowired
	private OpinionService opinionService;
	@Autowired
	private EarningsService earningsService;

	//종목 검색: /api/stocks?query=삼성  → {"list":[...]}
	@GetMapping("/stocks")
	public Map<String, List<StockDto>> searchStocks(@RequestParam(required = false) String query) {
		Map<String, List<StockDto>> map = new HashMap<>();
		map.put("list", stockService.searchStocks(query));
		return map;
	}

	//종목 정보: /api/stocks/005930 → {"stock":{...}}
	@GetMapping("/stocks/{symbol}")
	public Map<String, StockDto> getStock(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, StockDto> map = new HashMap<>();
		map.put("stock", stockService.getStock(symbol));
		return map;
	}

	//최신 시세 (Polling용): /api/stocks/005930/quote → {"quote":{...}}
	@GetMapping("/stocks/{symbol}/quote")
	public Map<String, QuoteDto> getQuote(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, QuoteDto> map = new HashMap<>();
		map.put("quote", quoteStore.getQuote(symbol));
		return map;
	}

	//여러 종목 최신 시세 (티커·관심 종목 Polling용): /api/quotes?symbols=005930,000660 → {"list":[...]}
	@GetMapping("/quotes")
	public Map<String, List<QuoteDto>> getQuotes(@RequestParam List<String> symbols) {
		List<QuoteDto> list = new ArrayList<>();
		for (String symbol : symbols) {
			if (!symbol.matches(SYMBOL_REGEX) || !quoteStore.isSupported(symbol)) continue;
			try {
				list.add(quoteStore.getQuote(symbol));
			} catch (MarketDataException e) {
				//한 종목이 실패해도 나머지는 응답한다
			}
		}
		Map<String, List<QuoteDto>> map = new HashMap<>();
		map.put("list", list);
		return map;
	}

	//선 차트용 이력: /api/stocks/005930/history?range=1D|1W|1M|1Y → {"history":{...}}
	@GetMapping("/stocks/{symbol}/history")
	public Map<String, ChartDto> getHistory(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol,
			@RequestParam(defaultValue = "1D") String range) {
		Map<String, ChartDto> map = new HashMap<>();
		map.put("history", chartService.getHistory(symbol, range));
		return map;
	}

	//캔들 OHLC: /api/stocks/005930/candles?interval=1s~60m|1d|1w|1mo|1y (일봉만 range=1W|1M|1Y) → {"candles":{...}}
	@GetMapping("/stocks/{symbol}/candles")
	public Map<String, ChartDto> getCandles(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol,
			@RequestParam(defaultValue = "1d") String interval,
			@RequestParam(required = false) String range) {
		Map<String, ChartDto> map = new HashMap<>();
		map.put("candles", chartService.getCandles(symbol, interval, range));
		return map;
	}

	//종목 관련 뉴스: /api/stocks/005930/news → {"symbol":"005930","news":[...]}
	@GetMapping("/stocks/{symbol}/news")
	public Map<String, Object> getNews(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		List<NewsDto> news = newsService.getNews(symbol);
		Map<String, Object> map = new HashMap<>();
		map.put("symbol", symbol);
		map.put("news", news);
		return map;
	}

	//전문가 투자의견: /api/stocks/005930/opinions → {"opinion":{...}}
	@GetMapping("/stocks/{symbol}/opinions")
	public Map<String, OpinionDto> getOpinion(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, OpinionDto> map = new HashMap<>();
		map.put("opinion", opinionService.getOpinion(symbol));
		return map;
	}

	//최근 실적: /api/stocks/005930/earnings → {"symbol":"005930","source":"KIS","unit":"억원","quarters":[...]}
	@GetMapping("/stocks/{symbol}/earnings")
	public Map<String, Object> getEarnings(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		List<EarningsDto> quarters = earningsService.getEarnings(symbol);
		Map<String, Object> map = new HashMap<>();
		map.put("symbol", symbol);
		map.put("source", earningsService.source());
		map.put("unit", EarningsService.UNIT);
		map.put("quarters", quarters);
		return map;
	}

	//시세 수신 상태: /api/market/status → {"status":{...}}
	@GetMapping("/market/status")
	public Map<String, MarketStatusDto> getStatus() {
		Map<String, MarketStatusDto> map = new HashMap<>();
		map.put("status", quoteStore.getStatus());
		return map;
	}
}
