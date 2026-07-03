package com.tonepilot.infrastructure.knowledge.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.domain.knowledge.KnowledgeChunk;
import com.tonepilot.domain.knowledge.KnowledgeExtractionJob;
import com.tonepilot.domain.knowledge.KnowledgeMaterial;
import com.tonepilot.domain.knowledge.KnowledgeSource;
import com.tonepilot.domain.knowledge.StyleKnowledge;
import com.tonepilot.infrastructure.shared.persistence.PersistenceProperties;
import com.tonepilot.repository.knowledge.KnowledgeCatalogRepository;
import com.tonepilot.repository.knowledge.KnowledgeChunkStorageRecord;
import com.tonepilot.repository.knowledge.StyleKnowledgeStorageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MybatisKnowledgeCatalogRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisKnowledgeCatalogRepository.class);

    private final PersistenceProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<KnowledgeCatalogRepository> repositoryProvider;

    @Autowired
    public MybatisKnowledgeCatalogRepository(
            PersistenceProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<KnowledgeCatalogRepository> repositoryProvider
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.repositoryProvider = repositoryProvider;
    }

    public void saveSource(KnowledgeSource source) {
        execute("knowledge_source", repository -> {
            if (repository.updateSource(source) == 0) {
                repository.insertSource(source);
            }
        });
    }

    public void saveMaterial(KnowledgeMaterial material) {
        execute("knowledge_material", repository -> {
            if (repository.updateMaterial(material) == 0) {
                repository.insertMaterial(material);
            }
        });
    }

    public void saveExtractionJob(KnowledgeExtractionJob job) {
        execute("knowledge_extraction_job", repository -> {
            if (repository.updateExtractionJob(job) == 0) {
                repository.insertExtractionJob(job);
            }
        });
    }

    public void saveStyleKnowledge(StyleKnowledge knowledge) {
        execute("style_knowledge", repository -> {
            StyleKnowledgeStorageRecord record = new StyleKnowledgeStorageRecord(
                knowledge.id(),
                knowledge.styleId(),
                knowledge.sampleId(),
                knowledge.title(),
                knowledge.scene(),
                knowledge.targetStyle(),
                json(knowledge.problems()),
                json(knowledge.strategy()),
                json(knowledge.paramRanges()),
                knowledge.content(),
                knowledge.embeddingId(),
                knowledge.status(),
                knowledge.createdAt(),
                knowledge.updatedAt()
            );
            if (repository.updateStyleKnowledge(record) == 0) {
                repository.insertStyleKnowledge(record);
            }
        });
    }

    public void replaceKnowledgeChunks(Long sourceId, List<KnowledgeChunk> chunks) {
        execute("knowledge_chunk", repository -> {
            repository.deleteKnowledgeChunks(sourceId);
            for (KnowledgeChunk chunk : chunks) {
                repository.saveKnowledgeChunk(new KnowledgeChunkStorageRecord(
                        chunk.id(),
                        chunk.sourceType(),
                        chunk.sourceId(),
                        chunk.title(),
                        chunk.content(),
                        json(chunk.embedding()),
                        chunk.createdAt()
                ));
            }
        });
    }

    private void execute(String table, RepositoryAction action) {
        KnowledgeCatalogRepository repository = repositoryProvider.getIfAvailable();
        if (!properties.isEnabled() || repository == null) {
            return;
        }
        try {
            action.accept(repository);
        } catch (Exception exception) {
            log.debug("知识库结构化表写入失败 table={}：{}", table, exception.getMessage());
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("知识库 JSON 序列化失败：" + exception.getMessage(), exception);
        }
    }

    private interface RepositoryAction {
        void accept(KnowledgeCatalogRepository repository);
    }
}
