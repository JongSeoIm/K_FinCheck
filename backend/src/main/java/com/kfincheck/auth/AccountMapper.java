package com.kfincheck.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AccountMapper {
    Account findByLoginId(String loginId);
    int insertAccount(@Param("loginId") String loginId, @Param("passwordHash") String passwordHash, @Param("nickname") String nickname);
    List<String> findWatchlist(long userId);
    int insertCompany(@Param("code") String code, @Param("name") String name, @Param("market") String market);
    int addWatchlist(@Param("userId") long userId, @Param("code") String code);
    int removeWatchlist(@Param("userId") long userId, @Param("code") String code);

    record Account(long userId, String loginId, String passwordHash, String nickname) { }
}
