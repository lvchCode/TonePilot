package com.tonepilot.repository.admin.data;

public record AdminDataDeleteCommand(
        String tableName,
        String primaryKey,
        Object primaryValue
) {
}
