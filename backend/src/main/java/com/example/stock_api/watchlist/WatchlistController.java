package com.example.stock_api.watchlist;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.example.stock_api.auth.LoginUser;
import com.example.stock_api.common.ApiException;
import com.example.stock_api.stock.StockService;

import jakarta.validation.constraints.Pattern;

/** 로그인한 사용자 본인의 관심 종목. 변경 요청도 바뀐 뒤 전체 목록을 돌려줍니다. */
@Validated
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private static final String CODE = "\\d{6}";

    private final WatchlistMapper watchlist;
    private final StockService stocks;

    public WatchlistController(WatchlistMapper watchlist, StockService stocks) {
        this.watchlist = watchlist;
        this.stocks = stocks;
    }

    @GetMapping
    public List<String> list(@SessionAttribute(name = LoginUser.SESSION_KEY, required = false) LoginUser user) {
        return watchlist.findCodes(required(user).userId());
    }

    @PostMapping("/{code}")
    public List<String> add(@SessionAttribute(name = LoginUser.SESSION_KEY, required = false) LoginUser user,
                            @PathVariable @Pattern(regexp = CODE) String code) {
        long userId = required(user).userId();
        if (stocks.find(code).isEmpty()) {
            throw ApiException.notSupported(code);
        }
        watchlist.add(userId, code);
        return watchlist.findCodes(userId);
    }

    @DeleteMapping("/{code}")
    public List<String> remove(@SessionAttribute(name = LoginUser.SESSION_KEY, required = false) LoginUser user,
                               @PathVariable @Pattern(regexp = CODE) String code) {
        long userId = required(user).userId();
        watchlist.remove(userId, code);
        return watchlist.findCodes(userId);
    }

    private static LoginUser required(LoginUser user) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다.");
        }
        return user;
    }
}
