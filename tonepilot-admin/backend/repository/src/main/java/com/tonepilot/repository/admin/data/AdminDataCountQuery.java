package com.tonepilot.repository.admin.data;

import java.util.List;

public record AdminDataCountQuery(
        String tableName,
        List<String> searchableColumns,
        String keyword
) {
}
