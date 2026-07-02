package com.tonepilot.application.knowledge;

import com.tonepilot.application.dto.StyleKnowledgeRequest;
import com.tonepilot.application.style.StyleSampleService;
import com.tonepilot.application.style.StyleService;
import com.tonepilot.domain.agent.KnowledgeGenerationAgent;
import com.tonepilot.domain.common.NotFoundException;
import com.tonepilot.domain.knowledge.StyleKnowledge;
import com.tonepilot.domain.style.ColorStyle;
import com.tonepilot.domain.style.StyleSample;
import com.tonepilot.infrastructure.ai.AiProviderContext;
import com.tonepilot.infrastructure.knowledge.catalog.KnowledgeCatalogJdbcRepository;
import com.tonepilot.infrastructure.shared.persistence.DomainSnapshotRepository;
import com.tonepilot.infrastructure.shared.persistence.InMemoryTonePilotStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StyleKnowledgeService {

    private final InMemoryTonePilotStore store;
    private final StyleService styleService;
    private final StyleSampleService styleSampleService;
    private final KnowledgeGenerationAgent knowledgeGenerationAgent;
    private final KnowledgeVectorIndexService knowledgeVectorIndexService;
    private final DomainSnapshotRepository snapshotRepository;
    private final KnowledgeCatalogJdbcRepository catalogRepository;

    @Autowired
    public StyleKnowledgeService(
            InMemoryTonePilotStore store,
            StyleService styleService,
            StyleSampleService styleSampleService,
            KnowledgeGenerationAgent knowledgeGenerationAgent,
            KnowledgeVectorIndexService knowledgeVectorIndexService,
            DomainSnapshotRepository snapshotRepository,
            KnowledgeCatalogJdbcRepository catalogRepository
    ) {
        this.store = store;
        this.styleService = styleService;
        this.styleSampleService = styleSampleService;
        this.knowledgeGenerationAgent = knowledgeGenerationAgent;
        this.knowledgeVectorIndexService = knowledgeVectorIndexService;
        this.snapshotRepository = snapshotRepository;
        this.catalogRepository = catalogRepository;
    }

    public StyleKnowledge generateFromSample(Long sampleId) {
        return generateFromSample(sampleId, null);
    }

    public StyleKnowledge generateFromSample(Long sampleId, String provider) {
        StyleSample sample = styleSampleService.get(sampleId);
        if (sample.analysisResult() == null) {
            sample = styleSampleService.analyze(sampleId, provider);
        }
        StyleSample analyzedSample = sample;
        ColorStyle style = styleService.get(analyzedSample.styleId());
        StyleKnowledge draft = AiProviderContext.use(
                provider,
                () -> knowledgeGenerationAgent.generate(style, analyzedSample, analyzedSample.analysisResult())
        );
        StyleKnowledge saved = new StyleKnowledge(
                store.styleKnowledgeIds.getAndIncrement(),
                draft.styleId(),
                draft.sampleId(),
                draft.title(),
                draft.scene(),
                draft.targetStyle(),
                draft.problems(),
                draft.strategy(),
                draft.paramRanges(),
                draft.content(),
                draft.embeddingId(),
                draft.status(),
                Instant.now(),
                Instant.now()
        );
        persist(saved);
        return saved;
    }

    public List<StyleKnowledge> list(String status) {
        return store.styleKnowledge.values().stream()
                .filter(item -> status == null || status.isBlank() || item.status().equals(status))
                .sorted(Comparator.comparing(StyleKnowledge::updatedAt).reversed())
                .toList();
    }

    public StyleKnowledge get(Long id) {
        StyleKnowledge knowledge = store.styleKnowledge.get(id);
        if (knowledge == null) {
            throw new NotFoundException("未找到风格知识：" + id);
        }
        return knowledge;
    }

    public StyleKnowledge update(Long id, StyleKnowledgeRequest request) {
        StyleKnowledge existing = get(id);
        StyleKnowledge updated = new StyleKnowledge(
                existing.id(),
                existing.styleId(),
                existing.sampleId(),
                valueOr(request.title(), existing.title()),
                valueOr(request.scene(), existing.scene()),
                valueOr(request.targetStyle(), existing.targetStyle()),
                request.problems() == null ? existing.problems() : request.problems(),
                request.strategy() == null ? existing.strategy() : request.strategy(),
                request.paramRanges() == null ? existing.paramRanges() : request.paramRanges(),
                valueOr(request.content(), existing.content()),
                existing.embeddingId() == null ? "local-style-" + UUID.randomUUID() : existing.embeddingId(),
                valueOr(request.status(), existing.status()),
                existing.createdAt(),
                Instant.now()
        );
        persist(updated);
        return updated;
    }

    public StyleKnowledge approve(Long id) {
        StyleKnowledge approved = changeStatus(id, "approved");
        knowledgeVectorIndexService.indexStyleKnowledge(approved);
        return approved;
    }

    public StyleKnowledge reject(Long id) {
        return changeStatus(id, "rejected");
    }

    public StyleKnowledge disable(Long id) {
        return changeStatus(id, "disabled");
    }

    public StyleKnowledge createDraftFromMaterial(
            Long styleId,
            String title,
            String scene,
            String targetStyle,
            List<String> problems,
            List<String> strategy,
            Map<String, String> paramRanges,
            String content
    ) {
        StyleKnowledge saved = new StyleKnowledge(
                store.styleKnowledgeIds.getAndIncrement(),
                styleId,
                null,
                valueOr(title, "导入素材生成的调色知识"),
                valueOr(scene, "通用摄影场景"),
                valueOr(targetStyle, "自然调色"),
                problems == null ? List.of() : problems,
                strategy == null ? List.of() : strategy,
                paramRanges == null ? Map.of() : paramRanges,
                valueOr(content, ""),
                "material-style-" + UUID.randomUUID(),
                "pending",
                Instant.now(),
                Instant.now()
        );
        persist(saved);
        return saved;
    }

    private StyleKnowledge changeStatus(Long id, String status) {
        StyleKnowledge existing = get(id);
        StyleKnowledge updated = new StyleKnowledge(
                existing.id(),
                existing.styleId(),
                existing.sampleId(),
                existing.title(),
                existing.scene(),
                existing.targetStyle(),
                existing.problems(),
                existing.strategy(),
                existing.paramRanges(),
                existing.content(),
                existing.embeddingId(),
                status,
                existing.createdAt(),
                Instant.now()
        );
        persist(updated);
        return updated;
    }

    private void persist(StyleKnowledge knowledge) {
        store.styleKnowledge.put(knowledge.id(), knowledge);
        snapshotRepository.save("style_knowledge", knowledge.id(), knowledge);
        catalogRepository.saveStyleKnowledge(knowledge);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
