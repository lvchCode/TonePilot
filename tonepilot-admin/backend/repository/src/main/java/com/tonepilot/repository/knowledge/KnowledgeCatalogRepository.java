package com.tonepilot.repository.knowledge;

import com.tonepilot.domain.knowledge.KnowledgeExtractionJob;
import com.tonepilot.domain.knowledge.KnowledgeMaterial;
import com.tonepilot.domain.knowledge.KnowledgeSource;

public interface KnowledgeCatalogRepository {

    int updateSource(KnowledgeSource source);

    int insertSource(KnowledgeSource source);

    int updateMaterial(KnowledgeMaterial material);

    int insertMaterial(KnowledgeMaterial material);

    int updateExtractionJob(KnowledgeExtractionJob job);

    int insertExtractionJob(KnowledgeExtractionJob job);

    int updateStyleKnowledge(StyleKnowledgeStorageRecord knowledge);

    int insertStyleKnowledge(StyleKnowledgeStorageRecord knowledge);

    void deleteKnowledgeChunks(Long sourceId);

    void saveKnowledgeChunk(KnowledgeChunkStorageRecord chunk);
}
