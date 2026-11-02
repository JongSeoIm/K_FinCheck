package com.kfincheck.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

@Service
public class AccountService {
    private final AccountMapper mapper;
    private final PasswordEncoder encoder;
    private final String dummyHash;
    private static final Map<String, String> COMPANIES = Map.of("005930", "삼성전자", "000660", "SK하이닉스",
            "035420", "NAVER", "035720", "카카오", "005380", "현대차", "000270", "기아", "051910", "LG화학", "006400", "삼성SDI");

    public AccountService(AccountMapper mapper, PasswordEncoder encoder) {
        this.mapper = mapper; this.encoder = encoder; this.dummyHash = encoder.encode("invalid-account-dummy");
    }

    public record User(long userId, String loginId, String nickname) implements java.io.Serializable { }
    public record Credentials(String loginId, String password) {
        @Override public String toString() { return "Credentials[hidden]"; }
    }
    public record Registration(String loginId, String password, String nickname) {
        @Override public String toString() { return "Registration[hidden]"; }
    }

    @Transactional
    public User signup(Registration request) {
        String id = request.loginId() == null ? "" : request.loginId().trim();
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        if (!id.matches("[A-Za-z0-9_]{4,30}")) throw bad("아이디는 영문·숫자·밑줄 4~30자로 입력해 주세요.");
        if (nickname.length() < 2 || nickname.length() > 30 || nickname.chars().anyMatch(Character::isISOControl))
            throw bad("닉네임은 2~30자로 입력해 주세요.");
        if (!validPassword(request.password())) throw bad("비밀번호는 8~64자, UTF-8 기준 72바이트 이내로 입력해 주세요.");
        try { mapper.insertAccount(id, encoder.encode(request.password()), nickname); }
        catch (DuplicateKeyException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."); }
        return view(mapper.findByLoginId(id));
    }

    public User login(Credentials request) {
        String id = request.loginId() == null ? "" : request.loginId().trim();
        if (!id.matches("[A-Za-z0-9_]{4,30}") || !validPassword(request.password()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해 주세요.");
        var account = mapper.findByLoginId(id);
        boolean matched = encoder.matches(request.password(), account == null ? dummyHash : account.passwordHash());
        if (!matched || account == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해 주세요.");
        return view(account);
    }

    public List<String> watchlist(long userId) { return mapper.findWatchlist(userId); }

    @Transactional
    public List<String> add(long userId, String code) {
        String name = COMPANIES.get(code);
        if (name == null) throw bad("현재 지원하는 종목만 관심 종목에 추가할 수 있습니다.");
        mapper.insertCompany(code, name, "KOSPI");
        mapper.addWatchlist(userId, code);
        return mapper.findWatchlist(userId);
    }

    public List<String> remove(long userId, String code) {
        mapper.removeWatchlist(userId, code);
        return mapper.findWatchlist(userId);
    }

    private static boolean validPassword(String value) {
        return value != null && value.length() >= 8 && value.length() <= 64
                && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    private static User view(AccountMapper.Account account) { return new User(account.userId(), account.loginId(), account.nickname()); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
