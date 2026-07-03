package com.tonepilot.repository.admin.data;

import java.util.List;

public record AdminDataInsertCommand(
        String tableName,
        List<String> columns,
        List<Object> values
) {
}
