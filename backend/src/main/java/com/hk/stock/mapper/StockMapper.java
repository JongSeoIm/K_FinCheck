package com.hk.stock.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.hk.stock.dtos.StockDto;

@Mapper
public interface StockMapper {
	public List<StockDto> getAllStocks();
	public List<StockDto> searchStocks(String query);
	public StockDto getStock(String symbol);
}
