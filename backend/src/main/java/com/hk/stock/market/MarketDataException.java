package com.hk.stock.market;

//외부 시세 제공처 호출 실패 (화면에는 502로 전달)
public class MarketDataException extends RuntimeException {
	public MarketDataException(String message) {
		super(message);
	}

	public MarketDataException(String message, Throwable cause) {
		super(message, cause);
	}
}
