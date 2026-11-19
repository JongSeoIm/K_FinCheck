package com.kfincheck.opinion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class OpinionController {
    private final OpinionService service;
    public OpinionController(OpinionService service) { this.service = service; }

    @GetMapping("/{stockCode}/opinions")
    public OpinionService.OpinionFeed latest(@PathVariable("stockCode") String stockCode) {
        return service.latest(stockCode);
    }

    @ExceptionHandler(OpinionService.OpinionException.class)
    public ResponseEntity<Map<String, String>> error(OpinionService.OpinionException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "error", error.getMessage()));
    }
}
