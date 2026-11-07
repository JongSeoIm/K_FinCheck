package com.hk.stock.service;

import com.hk.stock.dtos.QuoteDto;

//최신 시세가 바뀌었을 때 발행 → WebSocket 핸들러가 받아서 구독 브라우저에 Push
public record QuoteUpdatedEvent(QuoteDto quote) {
}
