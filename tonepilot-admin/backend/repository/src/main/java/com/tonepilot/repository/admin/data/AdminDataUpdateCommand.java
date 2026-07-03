package com.tonepilot.repository.admin.data;

import java.util.List;

public record AdminDataUpdateCommand(
        String tableName,
        List<AdminDataColumnValue> values,
        String primaryKey,
        Object primaryValue
) {
}
