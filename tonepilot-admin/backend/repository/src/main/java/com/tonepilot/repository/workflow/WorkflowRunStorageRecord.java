package com.tonepilot.repository.workflow;

import java.time.Instant;

public record WorkflowRunStorageRecord(
        String runId,
        Long photoId,
        String status,
        String provider,
        String targetStyle,
        String currentAgent,
        String storage,
        String snapshotJson,
        Instant createdAt,
        Instant updatedAt
) {
}
