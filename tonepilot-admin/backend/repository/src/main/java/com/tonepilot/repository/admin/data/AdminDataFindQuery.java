package com.tonepilot.repository.admin.data;

public record AdminDataFindQuery(
        String tableName,
        String columnSql,
        String primaryKey,
        Object primaryValue
) {
}
