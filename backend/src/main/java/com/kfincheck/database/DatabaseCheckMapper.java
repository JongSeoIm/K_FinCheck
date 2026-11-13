package com.kfincheck.database;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatabaseCheckMapper {
    int ping();
    String currentDatabase();
}
