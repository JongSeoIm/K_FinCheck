package com.hk.stock.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hk.stock.dtos.AnnualEarningsDto;
import com.hk.stock.dtos.CompanyDto;
import com.hk.stock.dtos.FinancialResultDto;
import com.hk.stock.service.FinancialService;

import jakarta.validation.constraints.Pattern;

//기업 정보·OpenDART 실적 (AI 챗봇 Tool get_company_info · get_financial_result 가 사용)
@RestController
@RequestMapping("/api/companies")
@Validated
public class CompanyController {

	private static final String SYMBOL_REGEX = "\\d{6}";
	private static final String SYMBOL_MESSAGE = "종목코드는 6자리 숫자입니다.";

	@Autowired
	private FinancialService financialService;

	//기업 정보: /api/companies/005930 → {"company":{symbol, name, market, dartCorpCode}}
	@GetMapping("/{symbol}")
	public Map<String, CompanyDto> getCompany(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, CompanyDto> map = new HashMap<>();
		map.put("company", financialService.getCompany(symbol));
		return map;
	}

	//실적: /api/companies/005930/financials?year=2025&report=11011 → {"financial":{...}}
	//year·report 를 비우면 가장 최근에 공시된 보고서
	@GetMapping("/{symbol}/financials")
	public Map<String, FinancialResultDto> getFinancials(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol,
			@RequestParam(required = false) String year,
			@RequestParam(required = false) String report) {
		Map<String, FinancialResultDto> map = new HashMap<>();
		map.put("financial", financialService.getFinancials(symbol, blankToNull(year), blankToNull(report)));
		return map;
	}

	//연간 실적·재무 지표: /api/companies/005930/annual-earnings → {"earnings":{...}}
	@GetMapping("/{symbol}/annual-earnings")
	public Map<String, AnnualEarningsDto> getAnnualEarnings(
			@PathVariable @Pattern(regexp = SYMBOL_REGEX, message = SYMBOL_MESSAGE) String symbol) {
		Map<String, AnnualEarningsDto> map = new HashMap<>();
		map.put("earnings", financialService.getAnnualEarnings(symbol));
		return map;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
