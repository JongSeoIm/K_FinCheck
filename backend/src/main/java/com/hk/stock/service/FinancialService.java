package com.hk.stock.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hk.stock.config.DartProperties;
import com.hk.stock.dtos.AnnualEarningsDto;
import com.hk.stock.dtos.CompanyDto;
import com.hk.stock.dtos.FinancialReportDto;
import com.hk.stock.dtos.FinancialResultDto;
import com.hk.stock.dtos.FinancialValueDto;
import com.hk.stock.mapper.FinancialMapper;
import com.hk.stock.market.MarketHours;
import com.hk.stock.service.StockService.StockNotFoundException;

/**
 * 기업·실적 (OpenDART → MariaDB)
 * - DB에 저장된 공시가 있으면 DB 값을 쓰고, 없으면 OpenDART에서 받아 저장한 뒤 돌려준다.
 * - 수집한 지 1시간이 지나면 다시 수집한다. 실패하면 저장된 값을 그대로 쓴다.
 * - 같은 공시(접수번호 + 연결/별도)는 한 번만 저장한다 (UNIQUE + INSERT IGNORE).
 * - 연결(CFS)이 있으면 연결, 없으면 별도(OFS) 기준으로 답한다.
 */
@Service
public class FinancialService {

	private static final Logger log = LoggerFactory.getLogger(FinancialService.class);
	public static final String SOURCE = "OpenDART";
	private static final String ANNUAL_REPORT = "11011";
	private static final long REFRESH_HOURS = 1;
	private static final Set<String> REPORT_CODES = Set.of("11013", "11012", "11014", "11011");
	//최근 순서: 사업보고서 → 3분기 → 반기 → 1분기
	private static final List<String> NEWEST_FIRST = List.of("11011", "11014", "11012", "11013");
	//OpenDART 재무 정보는 2015년 사업연도부터 제공된다
	private static final int FIRST_DART_YEAR = 2015;

	private final FinancialMapper mapper;
	private final DartClient dartClient;
	private final DartProperties props;
	private final TransactionTemplate transaction;
	//아직 공시되지 않은 보고서 (OpenDART 013) → 다시 물어보기 전까지 기다린다
	private final Map<String, Long> misses = new ConcurrentHashMap<>();

	public FinancialService(FinancialMapper mapper, DartClient dartClient, DartProperties props, TransactionTemplate transaction) {
		this.mapper = mapper;
		this.dartClient = dartClient;
		this.props = props;
		this.transaction = transaction;
	}

	public CompanyDto getCompany(String symbol) {
		CompanyDto company = mapper.getCompany(symbol);
		if (company == null) throw new StockNotFoundException(symbol);
		return company;
	}

	//기업 정보 화면: 가장 최근 사업보고서의 실적·전년 대비·재무 지표
	public AnnualEarningsDto getAnnualEarnings(String symbol) {
		FinancialResultDto report = getFinancials(symbol, null, ANNUAL_REPORT);
		return AnnualEarningsDto.builder()
				.symbol(report.getSymbol())
				.name(report.getName())
				.source(report.getSource())
				.bsnsYear(report.getBsnsYear())
				.reportName(report.getReportName())
				.fsName(report.getFsName())
				.url(report.getUrl())
				.unit(report.getUnit())
				.items(FinancialMetrics.earnings(report.getItems()))
				.metrics(report.getMetrics())
				.build();
	}

	//year·reprtCode 가 없으면 가장 최근에 공시된 보고서를 찾는다
	public FinancialResultDto getFinancials(String symbol, String year, String reprtCode) {
		if (year != null && !year.matches("\\d{4}")) {
			throw new IllegalArgumentException("사업연도는 4자리 숫자입니다. (예: 2025)");
		}
		if (reprtCode != null && !REPORT_CODES.contains(reprtCode)) {
			throw new IllegalArgumentException("보고서 종류는 11013(1분기)·11012(반기)·11014(3분기)·11011(사업보고서) 중 하나입니다.");
		}
		CompanyDto company = getCompany(symbol);
		if (company.getDartCorpCode() == null || company.getDartCorpCode().isBlank()) {
			throw new FinancialNotFoundException(company.getName() + "의 DART 기업 고유번호가 등록되지 않았습니다.");
		}

		if (year != null && Integer.parseInt(year) < FIRST_DART_YEAR) {
			throw new FinancialNotFoundException("OpenDART 실적은 " + FIRST_DART_YEAR + "년 이후 자료만 조회할 수 있습니다.");
		}

		for (String[] period : candidates(year, reprtCode)) {
			FinancialResultDto result = findOrFetch(company, period[0], period[1]);
			if (result != null) return result;
		}
		String which = (year == null ? "최근" : year + "년") + (reprtCode == null ? "" : " " + FinancialAccounts.reportName(reprtCode));
		throw new FinancialNotFoundException(company.getName() + "의 " + which + " 공시 실적을 찾지 못했습니다.");
	}

	//조회할 (사업연도, 보고서) 순서
	static List<String[]> candidates(String year, String reprtCode) {
		List<String[]> list = new ArrayList<>();
		int thisYear = LocalDate.now(MarketHours.KST).getYear();
		if (year != null && reprtCode != null) {
			list.add(new String[] { year, reprtCode });
		} else if (year != null) {
			for (String code : NEWEST_FIRST) list.add(new String[] { year, code });
		} else if (reprtCode != null) {
			//연도 없이 보고서만 물으면 그 보고서의 가장 최근 것 (올해 사업보고서는 다음 해에 나오므로 작년부터)
			int latest = "11011".equals(reprtCode) ? thisYear - 1 : thisYear;
			list.add(new String[] { String.valueOf(latest), reprtCode });
			list.add(new String[] { String.valueOf(latest - 1), reprtCode });
		} else {
			//올해 사업보고서는 다음 해에 나오므로 올해는 3분기부터, 작년은 사업보고서부터 찾는다 (최대 6번)
			for (String code : List.of("11014", "11012", "11013")) list.add(new String[] { String.valueOf(thisYear), code });
			for (String code : List.of("11011", "11014", "11012")) list.add(new String[] { String.valueOf(thisYear - 1), code });
		}
		return list;
	}

	private FinancialResultDto findOrFetch(CompanyDto company, String year, String reprtCode) {
		List<FinancialReportDto> reports = mapper.findReports(company.getSymbol(), year, reprtCode);
		if (reports.isEmpty()) {
			String key = company.getSymbol() + "-" + year + "-" + reprtCode;
			Long missedAt = misses.get(key);
			if (missedAt != null && System.currentTimeMillis() - missedAt < props.getMissCacheSeconds() * 1000L) {
				return null;
			}
			List<DartParsers.Row> rows = dartClient.fetchMainAccounts(company.getDartCorpCode(), year, reprtCode);
			if (rows.isEmpty()) {
				misses.put(key, System.currentTimeMillis());
				return null;
			}
			save(company.getSymbol(), rows);
			reports = mapper.findReports(company.getSymbol(), year, reprtCode);
			if (reports.isEmpty()) return null;
		} else if (isStale(reports.get(0))) {
			reports = refresh(company, year, reprtCode, reports);
		}

		//가장 최근 접수번호의 연결, 없으면 별도
		FinancialReportDto report = reports.stream().filter(r -> "CFS".equals(r.getFsDiv())).findFirst()
				.orElse(reports.get(0));
		return toResult(company, report, mapper.getValues(report.getReportId()));
	}

	private static boolean isStale(FinancialReportDto report) {
		LocalDateTime collectedAt = report.getCollectedAt();
		return collectedAt == null || collectedAt.isBefore(LocalDateTime.now().minusHours(REFRESH_HOURS));
	}

	//다시 수집한다. OpenDART 가 실패하거나 빈 응답이면 저장된 값을 그대로 쓰고, 수집 시각은 바꾸지 않는다
	private List<FinancialReportDto> refresh(CompanyDto company, String year, String reprtCode, List<FinancialReportDto> stored) {
		try {
			List<DartParsers.Row> rows = dartClient.fetchMainAccounts(company.getDartCorpCode(), year, reprtCode);
			if (rows.isEmpty()) return stored;
			save(company.getSymbol(), rows);
			return mapper.findReports(company.getSymbol(), year, reprtCode);
		} catch (DartClient.DartApiException e) {
			log.warn("OpenDART 재수집 실패, 저장된 값 사용 ({} {} {}): {}", company.getSymbol(), year, reprtCode, e.getMessage());
			return stored;
		}
	}

	//공시 → financial_report, 계정 → financial_value (한 트랜잭션)
	int save(String symbol, List<DartParsers.Row> rows) {
		Integer saved = transaction.execute(status -> {
			int count = 0;
			Set<Long> touched = new HashSet<>();
			for (DartParsers.Row row : rows) {
				FinancialReportDto report = mapper.getReport(row.rceptNo(), row.fsDiv());
				if (report == null) {
					mapper.insertReport(new FinancialReportDto(null, symbol, row.rceptNo(), row.bsnsYear(), row.reprtCode(), row.fsDiv(), null));
					report = mapper.getReport(row.rceptNo(), row.fsDiv());
				}
				if (touched.add(report.getReportId())) mapper.touchReport(report.getReportId());
				String code = FinancialAccounts.codeOf(row.accountName());
				count += mapper.insertValue(new FinancialValueDto(null, report.getReportId(),
						code, FinancialAccounts.nameOf(code, row.accountName()), null, row.accountName(),
						row.current(), row.previous(), row.currency(), row.current() == null ? "MISSING" : "AVAILABLE"));
			}
			return count;
		});
		return saved == null ? 0 : saved;
	}

	private FinancialResultDto toResult(CompanyDto company, FinancialReportDto report, List<FinancialValueDto> values) {
		List<FinancialResultDto.Item> items = values.stream()
				.sorted(Comparator.comparingInt(v -> FinancialAccounts.order(v.getAccountCode())))
				.map(v -> FinancialResultDto.Item.builder()
						.accountId(v.getAccountCode())
						.accountName(v.getSourceAccountName() != null ? v.getSourceAccountName() : v.getAccountName())
						.statement(FinancialAccounts.statementOf(v.getAccountCode()))
						.current(v.getCurrentAmount())
						.previous(v.getPreviousAmount())
						.currency(v.getCurrency())
						.build())
				.toList();
		return FinancialResultDto.builder()
				.symbol(company.getSymbol())
				.name(company.getName())
				.source(SOURCE)
				.rceptNo(report.getReceiptNo())
				.url("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + report.getReceiptNo())
				.bsnsYear(report.getBusinessYear())
				.reprtCode(report.getReportCode())
				.reportName(FinancialAccounts.reportName(report.getReportCode()))
				.fsDiv(report.getFsDiv())
				.fsName(FinancialAccounts.fsName(report.getFsDiv()))
				.incomeBasis(FinancialAccounts.incomeBasis(report.getReportCode()))
				.balanceBasis(FinancialAccounts.balanceBasis(report.getReportCode()))
				.unit("원")
				.items(items)
				.metrics(FinancialMetrics.metrics(items))
				.build();
	}

	public static class FinancialNotFoundException extends RuntimeException {
		public FinancialNotFoundException(String message) {
			super(message);
		}
	}
}
