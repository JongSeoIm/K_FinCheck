package com.hk.stock.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.StockDto;
import com.hk.stock.mapper.StockMapper;

@Service
public class StockService {

	@Autowired
	private StockMapper stockMapper;

	public List<StockDto> getAllStocks() {
		return stockMapper.getAllStocks();
	}

	public List<StockDto> searchStocks(String query) {
		if (query == null || query.isBlank()) {
			return stockMapper.getAllStocks();
		}
		return stockMapper.searchStocks(query.trim());
	}

	//지원하지 않는 종목이면 StockNotFoundException
	public StockDto getStock(String symbol) {
		StockDto stock = stockMapper.getStock(symbol);
		if (stock == null) {
			throw new StockNotFoundException(symbol);
		}
		return stock;
	}

	public static class StockNotFoundException extends RuntimeException {
		public StockNotFoundException(String symbol) {
			super("지원하지 않는 종목입니다: " + symbol);
		}
	}
}
