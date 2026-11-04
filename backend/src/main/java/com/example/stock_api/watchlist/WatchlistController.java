package com.example.stock_api.watchlist;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.stock_api.auth.LoginUser;
import com.example.stock_api.common.ApiException;
import com.example.stock_api.stock.StockService;

import jakarta.validation.constraints.Pattern;

/**
 * 로그인한 사용자 본인의 관심 종목. 로그인 확인은 SecurityConfig 에서 하고,
 * 회원 번호는 요청이 아니라 인증 정보에서만 가져옵니다. 변경 요청도 바뀐 뒤 전체 목록을 돌려줍니다.
 */
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
    public List<String> list(@AuthenticationPrincipal LoginUser user) {
        return watchlist.findCodes(user.userId());
    }

    @PostMapping("/{code}")
    public List<String> add(@AuthenticationPrincipal LoginUser user,
                            @PathVariable @Pattern(regexp = CODE) String code) {
        if (stocks.find(code).isEmpty()) {
            throw ApiException.notSupported(code);
        }
        watchlist.add(user.userId(), code);
        return watchlist.findCodes(user.userId());
    }

    @DeleteMapping("/{code}")
    public List<String> remove(@AuthenticationPrincipal LoginUser user,
                               @PathVariable @Pattern(regexp = CODE) String code) {
        watchlist.remove(user.userId(), code);
        return watchlist.findCodes(user.userId());
    }
}