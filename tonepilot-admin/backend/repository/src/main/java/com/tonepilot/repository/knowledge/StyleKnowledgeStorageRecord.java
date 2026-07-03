package com.tonepilot.repository.knowledge;

import java.time.Instant;

public record StyleKnowledgeStorageRecord(
        Long id,
        Long styleId,
        Long sampleId,
        String title,
        String scene,
        String targetStyle,
        String problemsJson,
        String strategyJson,
        String paramRangesJson,
        String content,
        String embeddingId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
