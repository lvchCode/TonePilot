package com.tonepilot.repository.knowledge;

import java.time.Instant;

public record KnowledgeChunkStorageRecord(
        Long id,
        String sourceType,
        Long sourceId,
        String title,
        String content,
        String embeddingJson,
        Instant createdAt
) {
}
