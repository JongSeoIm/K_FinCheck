package com.hk.stock.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hk.stock.config.DartProperties;
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
 * - 같은 공시(접수번호 + 연결/별도)는 한 번만 저장한다 (UNIQUE + INSERT IGNORE).
 * - 연결(CFS)이 있으면 연결, 없으면 별도(OFS) 기준으로 답한다.
 */
@Service
public class FinancialService {

	public static final String SOURCE = "OpenDART";
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
		}

		//가장 최근 접수번호의 연결, 없으면 별도
		FinancialReportDto report = reports.stream().filter(r -> "CFS".equals(r.getFsDiv())).findFirst()
				.orElse(reports.get(0));
		return toResult(company, report, mapper.getValues(report.getReportId()));
	}

	//공시 → financial_report, 계정 → financial_value (한 트랜잭션)
	int save(String symbol, List<DartParsers.Row> rows) {
		Integer saved = transaction.execute(status -> {
			int count = 0;
			for (DartParsers.Row row : rows) {
				FinancialReportDto report = mapper.getReport(row.rceptNo(), row.fsDiv());
				if (report == null) {
					mapper.insertReport(new FinancialReportDto(null, row.rceptNo(), symbol, row.bsnsYear(), row.reprtCode(), row.fsDiv(), null));
					report = mapper.getReport(row.rceptNo(), row.fsDiv());
				}
				count += mapper.insertValue(new FinancialValueDto(report.getReportId(),
						FinancialAccounts.codeOf(row.accountName()), row.accountName(),
						row.current(), row.previous(), row.currency()));
			}
			return count;
		});
		return saved == null ? 0 : saved;
	}

	private FinancialResultDto toResult(CompanyDto company, FinancialReportDto report, List<FinancialValueDto> values) {
		List<FinancialResultDto.Item> items = values.stream()
				.sorted(Comparator.comparingInt(v -> FinancialAccounts.order(v.getAccountId())))
				.map(v -> FinancialResultDto.Item.builder()
						.accountId(v.getAccountId())
						.accountName(v.getAccountNm())
						.statement(FinancialAccounts.statementOf(v.getAccountId()))
						.current(v.getThstrmAmount())
						.previous(v.getFrmtrmAmount())
						.currency(v.getCurrency())
						.build())
				.toList();
		return FinancialResultDto.builder()
				.symbol(company.getSymbol())
				.name(company.getName())
				.source(SOURCE)
				.rceptNo(report.getRceptNo())
				.url("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + report.getRceptNo())
				.bsnsYear(report.getBsnsYear())
				.reprtCode(report.getReprtCode())
				.reportName(FinancialAccounts.reportName(report.getReprtCode()))
				.fsDiv(report.getFsDiv())
				.fsName(FinancialAccounts.fsName(report.getFsDiv()))
				.incomeBasis(FinancialAccounts.incomeBasis(report.getReprtCode()))
				.balanceBasis(FinancialAccounts.balanceBasis(report.getReprtCode()))
				.unit("원")
				.items(items)
				.build();
	}

	public static class FinancialNotFoundException extends RuntimeException {
		public FinancialNotFoundException(String message) {
			super(message);
		}
	}
}
