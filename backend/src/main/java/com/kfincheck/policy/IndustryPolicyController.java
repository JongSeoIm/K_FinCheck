package com.kfincheck.policy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class IndustryPolicyController {
    private final IndustryPolicyService service;

    public IndustryPolicyController(IndustryPolicyService service) { this.service = service; }

    @GetMapping("/{stockCode}/policies")
    public IndustryPolicyService.PolicyFeed policies(@PathVariable("stockCode") String stockCode) {
        return service.latest(stockCode);
    }

    @ExceptionHandler(IndustryPolicyService.PolicyException.class)
    public ResponseEntity<Map<String, String>> error(IndustryPolicyService.PolicyException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
