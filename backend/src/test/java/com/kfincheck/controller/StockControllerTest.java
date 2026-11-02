package com.kfincheck.controller;

import com.kfincheck.dto.StockCandleDto;
import com.kfincheck.dto.StockPriceDto;
import com.kfincheck.service.StockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.context.annotation.Import(com.kfincheck.auth.SecurityConfig.class)
@WebMvcTest(StockController.class)
class StockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void getStockPriceReturnsDto() throws Exception {
        StockPriceDto mockDto = new StockPriceDto(
                "005930",
                "삼성전자",
                58000L,
                500L,
                0.87,
                1500000L,
                57800L,
                58500L,
                57600L,
                "KOSPI",
                "KIS"
        );

        given(stockService.getStockPrice("005930")).willReturn(mockDto);

        mockMvc.perform(get("/api/stocks/005930/price"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.stockName").value("삼성전자"))
                .andExpect(jsonPath("$.price").value(58000))
                .andExpect(jsonPath("$.priceChange").value(500))
                .andExpect(jsonPath("$.changeRate").value(0.87))
                .andExpect(jsonPath("$.source").value("KIS"));
    }

    @Test
    void getDailyCandlesReturnsList() throws Exception {
        List<StockCandleDto> mockCandles = List.of(
                new StockCandleDto("2026-10-30", 57000L, 58000L, 56500L, 57500L, 1000000L),
                new StockCandleDto("2026-10-31", 57500L, 58500L, 57000L, 58000L, 1200000L)
        );

        given(stockService.getDailyCandles("005930", 30, "D")).willReturn(mockCandles);

        mockMvc.perform(get("/api/stocks/005930/candles?days=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2026-10-30"))
                .andExpect(jsonPath("$[0].close").value(57500))
                .andExpect(jsonPath("$[1].date").value("2026-10-31"))
                .andExpect(jsonPath("$[1].close").value(58000));
    }
}
