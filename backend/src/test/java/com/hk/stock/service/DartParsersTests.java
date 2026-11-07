package com.hk.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hk.stock.dtos.DisclosureDto;

class DartParsersTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void 공시_목록은_지분_보고를_빼고_최신순_limit_개만_남긴다() throws Exception {
		List<DisclosureDto> list = DartParsers.disclosures(objectMapper.readTree("""
				{"status":"000","list":[
				 {"rcept_no":"20261106000001","report_nm":"임원ㆍ주요주주특정증권등소유상황보고서","rcept_dt":"20261106","flr_nm":"홍길동"},
				 {"rcept_no":"20261105000001","report_nm":"주식등의대량보유상황보고서(일반)","rcept_dt":"20261105","flr_nm":"국민연금공단"},
				 {"rcept_no":"bad","report_nm":"번호 오류","rcept_dt":"20261105","flr_nm":"삼성전자"},
				 {"rcept_no":"20261104000001","report_nm":"주요사항보고서(자기주식취득결정)","rcept_dt":"20261104","flr_nm":"삼성전자"},
				 {"rcept_no":"20261103000001","report_nm":"분기보고서 (2026.09)","rcept_dt":"20261103","flr_nm":"삼성전자"}
				]}
				"""), 1);

		assertThat(list).hasSize(1);
		assertThat(list.get(0).getReportName()).isEqualTo("주요사항보고서(자기주식취득결정)");
		assertThat(list.get(0).getFiledAt()).isEqualTo("2026-11-04");
		assertThat(list.get(0).getUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261104000001");
	}

	//OpenDART fnlttSinglAcnt 응답 형식 (값은 예시)
	private static final String SAMPLE = """
			{"status":"000","message":"정상","list":[
			 {"rcept_no":"20251114001234","bsns_year":"2025","stock_code":"005930","reprt_code":"11014",
			  "account_nm":"매출액","fs_div":"CFS","fs_nm":"연결재무제표","sj_div":"IS","sj_nm":"손익계산서",
			  "thstrm_amount":"86,061,747,000,000","frmtrm_amount":"79,098,697,000,000","ord":"19","currency":"KRW"},
			 {"rcept_no":"20251114001234","bsns_year":"2025","reprt_code":"11014","account_nm":"영업이익(손실)",
			  "fs_div":"CFS","sj_div":"IS","thstrm_amount":"-1,234","frmtrm_amount":"-","ord":"21","currency":"KRW"},
			 {"rcept_no":"20251114001234","bsns_year":"2025","reprt_code":"11014","account_nm":"자산총계",
			  "fs_div":"OFS","sj_div":"BS","thstrm_amount":"","ord":"3"},
			 {"rcept_no":"","account_nm":"버려질 행","fs_div":"CFS"}
			]}
			""";

	@Test
	void 정상_응답을_행으로_바꾼다() throws Exception {
		JsonNode body = objectMapper.readTree(SAMPLE);
		assertThat(DartParsers.status(body)).isEqualTo("000");

		List<DartParsers.Row> rows = DartParsers.rows(body);
		assertThat(rows).hasSize(3);	//접수번호 없는 행은 버린다

		DartParsers.Row revenue = rows.get(0);
		assertThat(revenue.rceptNo()).isEqualTo("20251114001234");
		assertThat(revenue.fsDiv()).isEqualTo("CFS");
		assertThat(revenue.current()).isEqualTo(86_061_747_000_000L);
		assertThat(revenue.previous()).isEqualTo(79_098_697_000_000L);
		assertThat(revenue.currency()).isEqualTo("KRW");
	}

	@Test
	void 음수와_빈값을_구분한다() throws Exception {
		List<DartParsers.Row> rows = DartParsers.rows(objectMapper.readTree(SAMPLE));
		assertThat(rows.get(1).current()).isEqualTo(-1_234L);
		assertThat(rows.get(1).previous()).isNull();	//"-" 는 0이 아니라 값 없음
		assertThat(rows.get(2).current()).isNull();
		assertThat(rows.get(2).currency()).isEqualTo("KRW");	//통화가 비면 KRW
	}

	@Test
	void 조회_결과_없음_상태를_읽는다() throws Exception {
		JsonNode body = objectMapper.readTree("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}");
		assertThat(DartParsers.status(body)).isEqualTo("013");
		assertThat(DartParsers.rows(body)).isEmpty();
	}

	@Test
	void 계정_이름을_고정_코드로_맞춘다() {
		assertThat(FinancialAccounts.codeOf("매출액")).isEqualTo("REVENUE");
		assertThat(FinancialAccounts.codeOf("수익(매출액)")).isEqualTo("REVENUE");
		assertThat(FinancialAccounts.codeOf("영업이익(손실)")).isEqualTo("OPERATING_INCOME");
		assertThat(FinancialAccounts.codeOf("분기순이익")).isEqualTo("NET_INCOME");
		assertThat(FinancialAccounts.codeOf("당기순이익(손실)")).isEqualTo("NET_INCOME");
		assertThat(FinancialAccounts.codeOf("법인세차감전 순이익")).isEqualTo("PRETAX_INCOME");
		assertThat(FinancialAccounts.codeOf("자산 총계")).isEqualTo("TOTAL_ASSETS");
		assertThat(FinancialAccounts.codeOf("새로운 계정")).isEqualTo("ETC_새로운계정");
		assertThat(FinancialAccounts.codeOf("가".repeat(60))).hasSize(50);
		assertThat(FinancialAccounts.nameOf("REVENUE", "수익(매출액)")).isEqualTo("매출액");
		assertThat(FinancialAccounts.nameOf("ETC_새로운계정", "새로운 계정")).isEqualTo("새로운 계정");
		assertThat(FinancialAccounts.statementOf("REVENUE")).isEqualTo("IS");
		assertThat(FinancialAccounts.statementOf("TOTAL_EQUITY")).isEqualTo("BS");
	}

	@Test
	void 최근_실적은_올해_3분기부터_작년까지_찾는다() {
		List<String[]> latest = FinancialService.candidates(null, null);
		assertThat(latest).hasSize(6);
		assertThat(latest.get(0)[1]).isEqualTo("11014");
		assertThat(latest.get(3)[1]).isEqualTo("11011");
		assertThat(Integer.parseInt(latest.get(3)[0])).isEqualTo(Integer.parseInt(latest.get(0)[0]) - 1);

		List<String[]> year = FinancialService.candidates("2024", null);
		assertThat(year).extracting(p -> p[1]).containsExactly("11011", "11014", "11012", "11013");

		assertThat(FinancialService.candidates("2024", "11012")).hasSize(1);
	}

	@Test
	void 연도_없이_보고서만_물으면_그_보고서의_최근_것을_찾는다() {
		int thisYear = java.time.LocalDate.now(com.hk.stock.market.MarketHours.KST).getYear();

		List<String[]> quarter = FinancialService.candidates(null, "11013");
		assertThat(quarter).extracting(p -> p[1]).containsOnly("11013");
		assertThat(quarter).extracting(p -> p[0]).containsExactly(String.valueOf(thisYear), String.valueOf(thisYear - 1));

		List<String[]> annual = FinancialService.candidates(null, "11011");
		assertThat(annual).extracting(p -> p[1]).containsOnly("11011");
		assertThat(annual).extracting(p -> p[0]).containsExactly(String.valueOf(thisYear - 1), String.valueOf(thisYear - 2));
	}
}
