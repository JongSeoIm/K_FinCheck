package com.example.stock_api.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class FinancialServiceTest {

    @Test
    void comparesProfitAndLoss() {
        assertEquals("NORMAL", FinancialService.comparison(110L, 100L));
        assertEquals("TURN_LOSS", FinancialService.comparison(-10L, 100L));
        assertEquals("TURN_PROFIT", FinancialService.comparison(10L, -100L));
        assertEquals("LOSS_CONTINUE", FinancialService.comparison(-10L, -100L));
    }

    @Test
    void missingOrZeroPreviousIsNotComparable() {
        assertEquals("NOT_COMPARABLE", FinancialService.comparison(null, 100L));
        assertEquals("NOT_COMPARABLE", FinancialService.comparison(100L, null));
        assertEquals("NOT_COMPARABLE", FinancialService.comparison(100L, 0L));
    }

    @Test
    void changeRateIsOnlyForNormalCase() {
        assertEquals(10.0, FinancialService.changeRate(110L, 100L));
        assertEquals(-33.3, FinancialService.changeRate(200L, 300L));
        assertNull(FinancialService.changeRate(-10L, 100L));
    }

    @Test
    void savedDataIsFreshForOneHour() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 12, 0);
        assertTrue(FinancialService.isFresh(now.minusMinutes(59), now));
        assertFalse(FinancialService.isFresh(now.minusMinutes(60), now));
        assertFalse(FinancialService.isFresh(null, now));
    }
}
