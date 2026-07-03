package com.tonepilot.repository.admin.data;

import java.util.List;

public record AdminDataListQuery(
        String tableName,
        String columnSql,
        String orderColumn,
        List<String> searchableColumns,
        String keyword,
        int limit,
        int offset
) {
}
