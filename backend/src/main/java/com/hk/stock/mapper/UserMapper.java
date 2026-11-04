package com.hk.stock.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hk.stock.dtos.UserDto;

@Mapper
public interface UserMapper {
	public int insertUser(UserDto user);
	public UserDto getUserByLoginId(String loginId);
	public UserDto getUserById(Long userId);
}
