package com.tonepilot.infrastructure.runtime.repository.mapper;

import java.time.Instant;

public record RuntimeDeviceStorageRecord(
        String id,
        String userId,
        String fingerprint,
        String deviceName,
        String endpoint,
        String metadataJson,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt
) {
}
