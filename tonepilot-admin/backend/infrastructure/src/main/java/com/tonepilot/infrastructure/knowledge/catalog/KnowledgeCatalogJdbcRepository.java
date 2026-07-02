package com.tonepilot.infrastructure.knowledge.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.domain.knowledge.KnowledgeChunk;
import com.tonepilot.domain.knowledge.KnowledgeExtractionJob;
import com.tonepilot.domain.knowledge.KnowledgeMaterial;
import com.tonepilot.domain.knowledge.KnowledgeSource;
import com.tonepilot.domain.knowledge.StyleKnowledge;
import com.tonepilot.infrastructure.shared.persistence.PersistenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class KnowledgeCatalogJdbcRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCatalogJdbcRepository.class);

    private final PersistenceProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    @Autowired
    public KnowledgeCatalogJdbcRepository(
            PersistenceProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public void saveSource(KnowledgeSource source) {
        execute("knowledge_source", () -> {
            JdbcTemplate jdbcTemplate = jdbc();
            int updated = jdbcTemplate.update("""
                    UPDATE knowledge_source
                    SET source_type = ?, title = ?, author = ?, original_url = ?, style_id = ?, notes = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    source.sourceType(), source.title(), source.author(), source.originalUrl(), source.styleId(),
                    source.notes(), source.status(), ts(source.updatedAt()), source.id());
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_source (
                            id, source_type, title, author, original_url, style_id, notes, status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        source.id(), source.sourceType(), source.title(), source.author(), source.originalUrl(), source.styleId(),
                        source.notes(), source.status(), ts(source.createdAt()), ts(source.updatedAt()));
            }
        });
    }

    public void saveMaterial(KnowledgeMaterial material) {
        execute("knowledge_material", () -> {
            JdbcTemplate jdbcTemplate = jdbc();
            int updated = jdbcTemplate.update("""
                    UPDATE knowledge_material
                    SET source_id = ?, material_type = ?, title = ?, content = ?, language = ?, created_at = ?
                    WHERE id = ?
                    """,
                    material.sourceId(), material.materialType(), material.title(), material.content(),
                    material.language(), ts(material.createdAt()), material.id());
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_material (
                            id, source_id, material_type, title, content, language, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        material.id(), material.sourceId(), material.materialType(), material.title(),
                        material.content(), material.language(), ts(material.createdAt()));
            }
        });
    }

    public void saveExtractionJob(KnowledgeExtractionJob job) {
        execute("knowledge_extraction_job", () -> {
            JdbcTemplate jdbcTemplate = jdbc();
            int updated = jdbcTemplate.update("""
                    UPDATE knowledge_extraction_job
                    SET source_id = ?, material_id = ?, status = ?, generated_knowledge_id = ?, message = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    job.sourceId(), job.materialId(), job.status(), job.generatedKnowledgeId(),
                    job.message(), ts(job.updatedAt()), job.id());
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_extraction_job (
                            id, source_id, material_id, status, generated_knowledge_id, message, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        job.id(), job.sourceId(), job.materialId(), job.status(), job.generatedKnowledgeId(),
                        job.message(), ts(job.createdAt()), ts(job.updatedAt()));
            }
        });
    }

    public void saveStyleKnowledge(StyleKnowledge knowledge) {
        execute("style_knowledge", () -> {
            JdbcTemplate jdbcTemplate = jdbc();
            String problems = json(knowledge.problems());
            String strategy = json(knowledge.strategy());
            String paramRanges = json(knowledge.paramRanges());
            int updated = jdbcTemplate.update("""
                    UPDATE style_knowledge
                    SET style_id = ?, sample_id = ?, title = ?, scene = ?, target_style = ?, problems_json = ?,
                        strategy_json = ?, param_ranges_json = ?, content = ?, embedding_id = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    knowledge.styleId(), knowledge.sampleId(), knowledge.title(), knowledge.scene(), knowledge.targetStyle(),
                    problems, strategy, paramRanges, knowledge.content(), knowledge.embeddingId(), knowledge.status(),
                    ts(knowledge.updatedAt()), knowledge.id());
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO style_knowledge (
                            id, style_id, sample_id, title, scene, target_style, problems_json, strategy_json,
                            param_ranges_json, content, embedding_id, status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        knowledge.id(), knowledge.styleId(), knowledge.sampleId(), knowledge.title(), knowledge.scene(),
                        knowledge.targetStyle(), problems, strategy, paramRanges, knowledge.content(), knowledge.embeddingId(),
                        knowledge.status(), ts(knowledge.createdAt()), ts(knowledge.updatedAt()));
            }
        });
    }

    public void replaceKnowledgeChunks(Long sourceId, List<KnowledgeChunk> chunks) {
        execute("knowledge_chunk", () -> {
            JdbcTemplate jdbcTemplate = jdbc();
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE source_type = ? AND source_id = ?", "style_knowledge_chunk", sourceId);
            for (KnowledgeChunk chunk : chunks) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_chunk (
                            id, source_type, source_id, title, content, embedding_json, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        chunk.id(), chunk.sourceType(), chunk.sourceId(), chunk.title(), chunk.content(),
                        json(chunk.embedding()), ts(chunk.createdAt()));
            }
        });
    }

    private void execute(String table, Runnable runnable) {
        if (!properties.isEnabled() || jdbcTemplateProvider.getIfAvailable() == null) {
            return;
        }
        try {
            runnable.run();
        } catch (Exception exception) {
            log.debug("知识库结构化表写入失败 table={}：{}", table, exception.getMessage());
        }
    }

    private JdbcTemplate jdbc() {
        return jdbcTemplateProvider.getObject();
    }

    private Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("知识库 JSON 序列化失败：" + exception.getMessage(), exception);
        }
    }
}
