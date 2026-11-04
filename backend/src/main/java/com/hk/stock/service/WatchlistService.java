package com.hk.stock.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.WatchlistDto;
import com.hk.stock.mapper.WatchlistMapper;

//관심 종목은 항상 세션의 회원 번호로만 조회·변경한다 (다른 계정 데이터 접근 차단)
@Service
public class WatchlistService {

	@Autowired
	private WatchlistMapper watchlistMapper;
	@Autowired
	private StockService stockService;

	public List<WatchlistDto> getWatchlist(Long userId) {
		return watchlistMapper.getWatchlist(userId);
	}

	//지원하지 않는 종목이면 StockNotFoundException (404)
	public List<WatchlistDto> add(Long userId, String symbol) {
		stockService.getStock(symbol);
		watchlistMapper.insertWatchlist(userId, symbol);
		return watchlistMapper.getWatchlist(userId);
	}

	//등록하지 않은 종목을 지워도 오류 없이 현재 목록을 돌려준다
	public List<WatchlistDto> remove(Long userId, String symbol) {
		watchlistMapper.deleteWatchlist(userId, symbol);
		return watchlistMapper.getWatchlist(userId);
	}
}
