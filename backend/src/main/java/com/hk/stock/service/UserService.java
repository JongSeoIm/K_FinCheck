package com.hk.stock.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.hk.stock.dtos.LoginRequest;
import com.hk.stock.dtos.SignupRequest;
import com.hk.stock.dtos.UserDto;
import com.hk.stock.mapper.UserMapper;

@Service
public class UserService {

	@Autowired
	private UserMapper userMapper;

	private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	//회원가입: 아이디 중복이면 DuplicateLoginIdException
	public UserDto signup(SignupRequest request) {
		String loginId = request.getLoginId().trim();
		if (userMapper.getUserByLoginId(loginId) != null) {
			throw new DuplicateLoginIdException();
		}
		UserDto user = new UserDto();
		user.setLoginId(loginId);
		user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
		user.setNickname(request.getNickname().trim());
		try {
			userMapper.insertUser(user);
		} catch (DuplicateKeyException e) {
			//동시에 같은 아이디로 가입한 경우 (UNIQUE 제약)
			throw new DuplicateLoginIdException();
		}
		return userMapper.getUserById(user.getUserId());
	}

	//로그인: 아이디가 없거나 비밀번호가 틀리면 같은 LoginFailedException (어느 쪽이 틀렸는지 알려주지 않는다)
	public UserDto login(LoginRequest request) {
		UserDto user = userMapper.getUserByLoginId(request.getLoginId().trim());
		if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
			throw new LoginFailedException();
		}
		return user;
	}

	//세션의 회원 번호로 조회 (탈퇴 등으로 없으면 null)
	public UserDto getUser(Long userId) {
		return userMapper.getUserById(userId);
	}

	public static class DuplicateLoginIdException extends RuntimeException {
		public DuplicateLoginIdException() {
			super("이미 사용 중인 아이디입니다.");
		}
	}

	public static class LoginFailedException extends RuntimeException {
		public LoginFailedException() {
			super("아이디 또는 비밀번호가 올바르지 않습니다.");
		}
	}
}
