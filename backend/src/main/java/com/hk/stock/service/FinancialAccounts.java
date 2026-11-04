package com.hk.stock.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenDART 주요계정 API 는 계정 코드(account_id) 없이 이름(account_nm)만 준다.
 * 회사·보고서마다 이름이 조금씩 달라서 ("당기순이익(손실)", "분기순이익" …) 고정 코드로 맞춘다.
 */
public final class FinancialAccounts {

	public static final String REVENUE = "REVENUE";
	public static final String OPERATING_INCOME = "OPERATING_INCOME";
	public static final String NET_INCOME = "NET_INCOME";

	//이름(공백 제거) → 코드. 괄호가 붙은 이름은 괄호를 지운 이름으로도 한 번 더 찾는다
	private static final Map<String, String> CODES = new LinkedHashMap<>();
	//코드 → 손익(IS) / 재무상태(BS)
	private static final Map<String, String> STATEMENTS = new LinkedHashMap<>();

	static {
		put("IS", REVENUE, "매출액", "수익(매출액)", "영업수익", "매출");
		put("IS", OPERATING_INCOME, "영업이익", "영업이익(손실)", "영업손실");
		put("IS", "PRETAX_INCOME", "법인세차감전순이익", "법인세비용차감전순이익", "법인세차감전순이익(손실)", "법인세비용차감전순이익(손실)");
		put("IS", NET_INCOME, "당기순이익", "당기순이익(손실)", "분기순이익", "분기순이익(손실)", "반기순이익", "반기순이익(손실)", "당기순손실");
		put("IS", "COMPREHENSIVE_INCOME", "총포괄손익", "총포괄이익", "당기총포괄손익");
		put("BS", "CURRENT_ASSETS", "유동자산");
		put("BS", "NON_CURRENT_ASSETS", "비유동자산");
		put("BS", "TOTAL_ASSETS", "자산총계");
		put("BS", "CURRENT_LIABILITIES", "유동부채");
		put("BS", "NON_CURRENT_LIABILITIES", "비유동부채");
		put("BS", "TOTAL_LIABILITIES", "부채총계");
		put("BS", "CAPITAL_STOCK", "자본금");
		put("BS", "RETAINED_EARNINGS", "이익잉여금", "이익잉여금(결손금)");
		put("BS", "TOTAL_EQUITY", "자본총계");
	}

	private FinancialAccounts() {
	}

	private static void put(String statement, String code, String... names) {
		STATEMENTS.put(code, statement);
		for (String name : names) CODES.put(name, code);
	}

	//알 수 없는 이름은 "ETC_" + 이름 (그래도 저장은 한다)
	public static String codeOf(String accountName) {
		String compact = accountName == null ? "" : accountName.replaceAll("\\s", "");
		String code = CODES.get(compact);
		if (code == null) code = CODES.get(compact.replaceAll("\\(.*?\\)", ""));
		if (code != null) return code;
		String etc = "ETC_" + compact;
		return etc.length() > 100 ? etc.substring(0, 100) : etc;
	}

	public static String statementOf(String code) {
		return STATEMENTS.getOrDefault(code, "ETC");
	}

	//화면·챗봇에 보여줄 순서 (손익 → 재무상태 → 기타)
	public static int order(String code) {
		int i = 0;
		for (String known : STATEMENTS.keySet()) {
			if (known.equals(code)) return i;
			i++;
		}
		return i;
	}

	public static String reportName(String reprtCode) {
		return switch (reprtCode) {
			case "11013" -> "1분기보고서";
			case "11012" -> "반기보고서";
			case "11014" -> "3분기보고서";
			case "11011" -> "사업보고서";
			default -> reprtCode;
		};
	}

	public static String fsName(String fsDiv) {
		return "CFS".equals(fsDiv) ? "연결" : "OFS".equals(fsDiv) ? "별도" : fsDiv;
	}

	//손익 항목 당기·전기 금액이 무엇인지 (분기·반기 보고서는 3개월 값, 사업보고서는 1년 값)
	public static String incomeBasis(String reprtCode) {
		return "11011".equals(reprtCode)
				? "사업연도 1년 금액, 전기는 직전 사업연도"
				: "해당 분기 3개월 금액, 전기는 전년 같은 분기";
	}

	public static String balanceBasis(String reprtCode) {
		return "11011".equals(reprtCode)
				? "사업연도 말 잔액, 전기는 직전 사업연도 말"
				: "보고 기간 말 잔액, 전기는 직전 사업연도 말";
	}
}
