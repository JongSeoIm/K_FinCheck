package com.hk.stock.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.hk.stock.market.MarketDataException;
import com.hk.stock.service.ChatService.AiServerException;
import com.hk.stock.service.DartClient.DartApiException;
import com.hk.stock.service.FinancialService.FinancialNotFoundException;
import com.hk.stock.service.NewsService.NewsApiException;
import com.hk.stock.service.StockService.StockNotFoundException;
import com.hk.stock.service.UserService.DuplicateLoginIdException;
import com.hk.stock.service.UserService.LoginFailedException;

import jakarta.validation.ConstraintViolationException;

//오류 응답 형식 통일: {"error":"메시지"}
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(StockNotFoundException.class)
	public ResponseEntity<Map<String, String>> notFound(StockNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler({ IllegalArgumentException.class, ConstraintViolationException.class,
			HandlerMethodValidationException.class, MissingServletRequestParameterException.class,
			MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
	public ResponseEntity<Map<String, String>> badRequest(Exception e) {
		//검증 오류는 "메서드명.파라미터명: " 접두어 없이 메시지만 전달
		String message;
		if (e instanceof MethodArgumentNotValidException v) {
			message = v.getBindingResult().getAllErrors().stream().map(err -> err.getDefaultMessage()).findFirst().orElse("잘못된 요청입니다.");
		} else if (e instanceof HttpMessageNotReadableException) {
			message = "요청 형식이 올바르지 않습니다.";
		} else if (e instanceof HandlerMethodValidationException v) {
			message = v.getAllErrors().stream().map(err -> err.getDefaultMessage()).findFirst().orElse("잘못된 요청입니다.");
		} else if (e instanceof ConstraintViolationException v) {
			message = v.getConstraintViolations().stream().map(cv -> cv.getMessage()).findFirst().orElse("잘못된 요청입니다.");
		} else {
			message = e.getMessage();
		}
		return error(HttpStatus.BAD_REQUEST, message);
	}

	//외부 시세 제공처 오류 → 502 (화면에서는 "시세를 불러오지 못했습니다" 표시)
	@ExceptionHandler(MarketDataException.class)
	public ResponseEntity<Map<String, String>> upstream(MarketDataException e) {
		log.warn("시세 제공처 오류: {}", e.getMessage());
		return error(HttpStatus.BAD_GATEWAY, "시세 제공처에서 데이터를 가져오지 못했습니다.");
	}

	//뉴스 API 오류 → 원인별 상태 코드와 안내 문구 (키 미설정 503, 키 오류 401, 한도 초과 429, 그 외 502)
	@ExceptionHandler(NewsApiException.class)
	public ResponseEntity<Map<String, String>> news(NewsApiException e) {
		return error(e.getStatus(), e.getMessage());
	}

	//OpenDART 오류 → 원인별 상태 코드 (키 미설정·키 오류 503, 한도 초과 429, 그 외 502)
	@ExceptionHandler(DartApiException.class)
	public ResponseEntity<Map<String, String>> dart(DartApiException e) {
		return error(e.getStatus(), e.getMessage());
	}

	//AI 서버 연결 실패 503, 시간 초과 504, 그 외 502
	@ExceptionHandler(AiServerException.class)
	public ResponseEntity<Map<String, String>> ai(AiServerException e) {
		return error(e.getStatus(), e.getMessage());
	}

	//공시된 실적이 없음 → 404
	@ExceptionHandler(FinancialNotFoundException.class)
	public ResponseEntity<Map<String, String>> financialNotFound(FinancialNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, e.getMessage());
	}

	//회원가입 아이디 중복 → 409
	@ExceptionHandler(DuplicateLoginIdException.class)
	public ResponseEntity<Map<String, String>> conflict(DuplicateLoginIdException e) {
		return error(HttpStatus.CONFLICT, e.getMessage());
	}

	//로그인 실패 → 401
	@ExceptionHandler(LoginFailedException.class)
	public ResponseEntity<Map<String, String>> unauthorized(LoginFailedException e) {
		return error(HttpStatus.UNAUTHORIZED, e.getMessage());
	}

	private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
	}
}
