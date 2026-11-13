package com.kfincheck.financial;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** AI 서버의 사업보고서 RAG 가 쓰는 API. 원문 파싱·청크·벡터화는 AI 서버가 한다. */
@RestController
@RequestMapping("/api/stocks")
public class BusinessReportController {
    private final BusinessReportService service;

    public BusinessReportController(BusinessReportService service) {
        this.service = service;
    }

    /** 사업보고서 정보 (접수번호, 사업연도, 원문 링크). year 가 없으면 최신 보고서. */
    @GetMapping("/{stockCode}/business-report")
    public BusinessReportService.Report report(@PathVariable("stockCode") String stockCode,
            @RequestParam(value = "year", required = false) Integer year) {
        return service.find(stockCode, year);
    }

    /** 사업보고서 원문 ZIP. 응답 헤더 X-Receipt-No 로 접수번호를 함께 준다. year 가 없으면 최신 보고서. */
    @GetMapping("/{stockCode}/business-report/document")
    public ResponseEntity<byte[]> document(@PathVariable("stockCode") String stockCode,
            @RequestParam(value = "year", required = false) Integer year) {
        var report = service.find(stockCode, year);
        return ResponseEntity.ok()
                .header("X-Receipt-No", report.receiptNo())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(service.document(report.receiptNo()));
    }

    @ExceptionHandler(FinancialException.class)
    public ResponseEntity<Map<String, String>> error(FinancialException error) {
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
