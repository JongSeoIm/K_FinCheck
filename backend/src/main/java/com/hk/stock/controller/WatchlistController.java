package com.hk.stock.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.hk.stock.config.SecurityConfig;
import com.hk.stock.dtos.WatchlistDto;
import com.hk.stock.dtos.WatchlistRequest;
import com.hk.stock.service.WatchlistService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

//계정별 관심 종목 (SecurityConfig 가 로그인 여부를 먼저 확인한다)
//모든 응답은 변경 후 전체 목록: {"list":[{symbol, name, market, createdAt}...]}
@RestController
@RequestMapping("/api/watchlist")
@Validated
public class WatchlistController {

	@Autowired
	private WatchlistService watchlistService;

	//내 관심 종목: GET /api/watchlist
	@GetMapping
	public Map<String, List<WatchlistDto>> getWatchlist(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId) {
		return listMap(watchlistService.getWatchlist(userId));
	}

	//추가: POST /api/watchlist {"symbol":"005930"}
	@PostMapping
	public Map<String, List<WatchlistDto>> add(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@Valid @RequestBody WatchlistRequest body) {
		return listMap(watchlistService.add(userId, body.getSymbol()));
	}

	//삭제: DELETE /api/watchlist/005930
	@DeleteMapping("/{symbol}")
	public Map<String, List<WatchlistDto>> remove(@SessionAttribute(SecurityConfig.LOGIN_USER_ID) Long userId,
			@PathVariable @Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자입니다.") String symbol) {
		return listMap(watchlistService.remove(userId, symbol));
	}

	private Map<String, List<WatchlistDto>> listMap(List<WatchlistDto> list) {
		Map<String, List<WatchlistDto>> map = new HashMap<>();
		map.put("list", list);
		return map;
	}
}
