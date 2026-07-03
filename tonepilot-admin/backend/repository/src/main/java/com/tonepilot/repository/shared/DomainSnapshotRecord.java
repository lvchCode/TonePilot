package com.tonepilot.repository.shared;

import java.time.Instant;

public record DomainSnapshotRecord(
        String domainType,
        String domainId,
        String payloadJson,
        Instant createdAt,
        Instant updatedAt
) {
}
