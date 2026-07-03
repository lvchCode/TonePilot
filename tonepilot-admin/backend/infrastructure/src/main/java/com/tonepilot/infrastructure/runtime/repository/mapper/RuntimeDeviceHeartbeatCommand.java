package com.tonepilot.infrastructure.runtime.repository.mapper;

import java.time.Instant;

public record RuntimeDeviceHeartbeatCommand(
        String id,
        String deviceName,
        String endpoint,
        String metadataJson,
        Instant lastSeenAt,
        Instant updatedAt
) {
}
