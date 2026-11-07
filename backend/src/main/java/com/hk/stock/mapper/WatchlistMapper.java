package com.hk.stock.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.hk.stock.dtos.WatchlistDto;

@Mapper
public interface WatchlistMapper {
	public List<WatchlistDto> getWatchlist(Long userId);
	public int insertWatchlist(@Param("userId") Long userId, @Param("symbol") String symbol);
	public int deleteWatchlist(@Param("userId") Long userId, @Param("symbol") String symbol);
}
