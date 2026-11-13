package com.kfincheck.rag;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 서버 전용 사업보고서 RAG 저장·검색 API. AI 서버는 DB 에 직접 붙지 않고 이 API 로만 저장·검색한다.
 * 같은 PC(localhost)에서 온 요청만 받는다.
 */
@RestController
@RequestMapping("/api/rag/reports")
public class ReportVectorController {
    public record SearchRequest(double[] embedding, Integer k) { }

    private final ReportVectorStore store;

    public ReportVectorController(ReportVectorStore store) {
        this.store = store;
    }

    @GetMapping("/{receiptNo}")
    public ResponseEntity<?> info(@PathVariable("receiptNo") String receiptNo, HttpServletRequest http) {
        requireLocal(http);
        return store.info(receiptNo).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> body(HttpStatus.NOT_FOUND, "NOT_INDEXED", "색인되지 않은 보고서입니다."));
    }

    @PutMapping("/{receiptNo}")
    public ResponseEntity<Void> save(@PathVariable("receiptNo") String receiptNo, @RequestBody ReportVectorStore.SaveRequest request,
                                     HttpServletRequest http) {
        requireLocal(http);
        store.save(receiptNo, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{receiptNo}/search")
    public ReportVectorStore.SearchResult search(@PathVariable("receiptNo") String receiptNo, @RequestBody SearchRequest request,
                                                 HttpServletRequest http) {
        requireLocal(http);
        return store.search(receiptNo, request == null ? null : request.embedding(), request == null || request.k() == null ? 5 : request.k());
    }

    @GetMapping("/{receiptNo}/chunks")
    public List<ReportVectorStore.Text> chunks(@PathVariable("receiptNo") String receiptNo, HttpServletRequest http) {
        requireLocal(http);
        return store.texts(receiptNo);
    }

    private static void requireLocal(HttpServletRequest http) {
        String address = http.getRemoteAddr();
        if (!("127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address))) {
            throw new SecurityException("이 API 는 같은 PC 에서만 호출할 수 있습니다.");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException error) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", error.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code, "error", message));
    }
}
