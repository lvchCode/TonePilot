package com.tonepilot.infrastructure.runtime.repository.mapper;

import java.time.Instant;

public record RuntimeUserStorageRecord(
        String id,
        String displayName,
        String source,
        Instant createdAt,
        Instant updatedAt
) {
}
