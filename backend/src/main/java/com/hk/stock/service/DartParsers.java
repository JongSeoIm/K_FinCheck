package com.hk.stock.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

//OpenDART 단일회사 주요계정(fnlttSinglAcnt) 응답 → 행 목록 (테스트하기 쉽도록 HTTP 호출과 분리)
public final class DartParsers {

	private DartParsers() {
	}

	//응답 한 줄: 연결(CFS)·별도(OFS), 손익(IS)·재무상태(BS) 행이 섞여서 온다
	public record Row(String rceptNo, String bsnsYear, String reprtCode, String fsDiv, String sjDiv,
			String accountName, Long current, Long previous, String currency, int ord) {
	}

	//status: 000 정상, 013 조회된 데이터 없음 (나머지는 오류)
	public static String status(JsonNode body) {
		String status = body.path("status").asText("");
		return status.isEmpty() ? body.path("result").path("status").asText("") : status;
	}

	public static String message(JsonNode body) {
		String message = body.path("message").asText("");
		return message.isEmpty() ? body.path("result").path("message").asText("") : message;
	}

	public static List<Row> rows(JsonNode body) {
		List<Row> rows = new ArrayList<>();
		for (JsonNode item : body.path("list")) {
			String rceptNo = item.path("rcept_no").asText("");
			String fsDiv = item.path("fs_div").asText("");
			String accountName = item.path("account_nm").asText("").trim();
			if (rceptNo.isEmpty() || fsDiv.isEmpty() || accountName.isEmpty()) continue;
			String currency = item.path("currency").asText("");
			rows.add(new Row(
					rceptNo,
					item.path("bsns_year").asText(""),
					item.path("reprt_code").asText(""),
					fsDiv,
					item.path("sj_div").asText(""),
					accountName,
					amount(item.path("thstrm_amount").asText(null)),
					amount(item.path("frmtrm_amount").asText(null)),
					currency.isBlank() ? "KRW" : currency,
					item.path("ord").asInt(0)));
		}
		return rows;
	}

	//"1,234,567" / "-1,234" / "-" / "" → 숫자 또는 null (값 없음을 0으로 바꾸지 않는다)
	public static Long amount(String text) {
		if (text == null) return null;
		String cleaned = text.replace(",", "").trim();
		if (cleaned.isEmpty() || cleaned.equals("-")) return null;
		try {
			return Long.parseLong(cleaned);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
