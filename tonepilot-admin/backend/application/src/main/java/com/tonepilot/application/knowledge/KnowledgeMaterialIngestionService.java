package com.tonepilot.application.knowledge;

import com.tonepilot.application.dto.KnowledgeMaterialRequest;
import com.tonepilot.application.dto.KnowledgeSourceRequest;
import com.tonepilot.domain.common.NotFoundException;
import com.tonepilot.domain.knowledge.DouyinImportRequest;
import com.tonepilot.domain.knowledge.KnowledgeExtractionJob;
import com.tonepilot.domain.knowledge.KnowledgeMaterial;
import com.tonepilot.domain.knowledge.KnowledgeSource;
import com.tonepilot.domain.knowledge.StyleKnowledge;
import com.tonepilot.domain.storage.ObjectStorageService;
import com.tonepilot.domain.storage.StoredFile;
import com.tonepilot.infrastructure.ai.AiProperties;
import com.tonepilot.infrastructure.ai.OpenAiCompatibleModelClient;
import com.tonepilot.infrastructure.ai.dto.StyleKnowledgeModelOutput;
import com.tonepilot.infrastructure.knowledge.catalog.KnowledgeCatalogJdbcRepository;
import com.tonepilot.infrastructure.knowledge.douyin.DouyinTranscriptService;
import com.tonepilot.infrastructure.knowledge.douyin.VideoTranscriptService;
import com.tonepilot.infrastructure.shared.persistence.DomainSnapshotRepository;
import com.tonepilot.infrastructure.shared.persistence.InMemoryTonePilotStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeMaterialIngestionService {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "m4v", "webm");

    @Autowired
    private InMemoryTonePilotStore store;
    @Autowired
    private StyleKnowledgeService styleKnowledgeService;
    @Autowired
    private DomainSnapshotRepository snapshotRepository;
    @Autowired
    private KnowledgeCatalogJdbcRepository catalogRepository;
    @Autowired
    private DouyinTranscriptService douyinTranscriptService;
    @Autowired
    private VideoTranscriptService videoTranscriptService;
    @Autowired
    private ObjectStorageService storageService;
    @Autowired
    private AiProperties aiProperties;
    @Autowired
    private OpenAiCompatibleModelClient modelClient;

    public KnowledgeSource createSource(KnowledgeSourceRequest request) {
        Instant now = Instant.now();
        KnowledgeSource source = new KnowledgeSource(
                store.knowledgeSourceIds.getAndIncrement(),
                trimOrDefault(request.sourceType(), "manual_note"),
                trimOrDefault(request.title(), "未命名调色素材"),
                trimOrDefault(request.author(), "未知作者"),
                trimOrDefault(request.originalUrl(), ""),
                request.styleId(),
                trimOrDefault(request.notes(), ""),
                "enabled",
                now,
                now
        );
        persistSource(source);
        return source;
    }

    public List<KnowledgeSource> listSources() {
        return store.knowledgeSources.values().stream()
                .sorted(Comparator.comparing(KnowledgeSource::updatedAt).reversed())
                .toList();
    }

    public KnowledgeMaterial importMaterial(Long sourceId, KnowledgeMaterialRequest request) {
        KnowledgeSource source = getSource(sourceId);
        KnowledgeMaterial material = new KnowledgeMaterial(
                store.knowledgeMaterialIds.getAndIncrement(),
                source.id(),
                trimOrDefault(request.materialType(), "manual_text"),
                trimOrDefault(request.title(), "调色素材"),
                request.content().trim(),
                trimOrDefault(request.language(), "zh-CN"),
                Instant.now()
        );
        persistMaterial(material);
        return material;
    }

    public List<KnowledgeMaterial> listMaterials(Long sourceId) {
        getSource(sourceId);
        return store.knowledgeMaterials.values().stream()
                .filter(item -> item.sourceId().equals(sourceId))
                .sorted(Comparator.comparing(KnowledgeMaterial::createdAt).reversed())
                .toList();
    }

    public KnowledgeExtractionJob importDouyinVideo(DouyinImportRequest request) {
        String transcript = douyinTranscriptService.extractTranscript(request);
        KnowledgeSource source = createSource(new KnowledgeSourceRequest(
                "douyin_video",
                trimOrDefault(request.title(), "抖音调色教程"),
                trimOrDefault(request.author(), "未知作者"),
                extractOriginalUrl(request.videoUrl()),
                request.styleId(),
                trimOrDefault(request.notes(), "")
        ));
        KnowledgeMaterial material = importMaterial(source.id(), new KnowledgeMaterialRequest(
                "transcript",
                source.title() + " 字幕/摘要",
                transcript,
                "zh-CN"
        ));
        return extractToKnowledge(source.id(), material.id());
    }

    public KnowledgeExtractionJob importUploadedDouyinVideo(MultipartFile file, String title, String author, Long styleId, String notes) {
        StoredFile storedFile = storageService.storeFile(
                file,
                "knowledge-videos",
                VIDEO_EXTENSIONS,
                "视频文件不能为空",
                "仅支持 MP4、MOV、M4V、WEBM 视频文件"
        );
        Path transcribePath = storedFile.path();
        boolean temporaryCopy = false;
        try {
            if (transcribePath == null) {
                transcribePath = Files.createTempFile("tonepilot-video-", "." + storedFile.fileType());
                Files.write(transcribePath, storageService.readObject(storedFile.fileUrl()).bytes());
                temporaryCopy = true;
            }
            String sourceTitle = trimOrDefault(title, "上传抖音调色教程");
            String transcript = videoTranscriptService.transcribeVideo(transcribePath, storedFile.fileName(), sourceTitle, author, notes);
            KnowledgeSource source = createSource(new KnowledgeSourceRequest(
                    "douyin_uploaded_video",
                    sourceTitle,
                    trimOrDefault(author, "未知作者"),
                    storedFile.fileUrl(),
                    styleId,
                    buildUploadedVideoNotes(notes, storedFile)
            ));
            KnowledgeMaterial material = importMaterial(source.id(), new KnowledgeMaterialRequest(
                    "transcript",
                    source.title() + " 上传视频字幕/摘要",
                    transcript,
                    "zh-CN"
            ));
            return extractToKnowledge(source.id(), material.id());
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("上传视频导入知识失败：" + exception.getMessage(), exception);
        } finally {
            if (temporaryCopy && transcribePath != null) {
                try {
                    Files.deleteIfExists(transcribePath);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响导入结果。
                }
            }
        }
    }

    public KnowledgeExtractionJob extractToKnowledge(Long sourceId, Long materialId) {
        KnowledgeSource source = getSource(sourceId);
        KnowledgeMaterial material = getMaterial(source.id(), materialId);
        StyleKnowledge knowledge = createDraftKnowledge(source, material);
        Instant now = Instant.now();
        KnowledgeExtractionJob job = new KnowledgeExtractionJob(
                store.knowledgeExtractionJobIds.getAndIncrement(),
                source.id(),
                material.id(),
                "succeeded",
                knowledge.id(),
                "已从素材生成待审核知识",
                now,
                now
        );
        store.knowledgeExtractionJobs.put(job.id(), job);
        snapshotRepository.save("knowledge_extraction_job", job.id(), job);
        catalogRepository.saveExtractionJob(job);
        return job;
    }

    private StyleKnowledge createDraftKnowledge(KnowledgeSource source, KnowledgeMaterial material) {
        if (aiProperties.modelEnabled()) {
            try {
                String json = modelClient.completeJson(
                        "你是 TonePilot 调色知识抽取 Agent，只输出严格 JSON。",
                        """
                                请从下面的调色素材中抽取一条可审核、可检索的摄影调色知识。
                                字段必须为 title, scene, problems, targetStyle, strategy, paramRanges, content。
                                要保留来源信息，不要承诺复刻某个博主或盗用预设。

                                来源：
                                %s

                                素材：
                                %s
                                """.formatted(buildSourceSummary(source), material.content())
                );
                StyleKnowledgeModelOutput output = modelClient.readJson(json, StyleKnowledgeModelOutput.class);
                return styleKnowledgeService.createDraftFromMaterial(
                        source.styleId(),
                        trimOrDefault(output.title(), source.title() + " - " + material.title()),
                        trimOrDefault(output.scene(), inferScene(source, material)),
                        trimOrDefault(output.targetStyle(), inferTargetStyle(source)),
                        output.problems() == null ? inferProblems(material) : output.problems(),
                        output.strategy() == null ? inferStrategy(material) : output.strategy(),
                        output.paramRanges() == null ? inferParamRanges(material) : output.paramRanges(),
                        buildKnowledgeContent(source, material) + "\n\n模型抽取：\n" + trimOrDefault(output.content(), "")
                );
            } catch (Exception ignored) {
                // 模型抽取失败时保留导入链路，生成待审核草稿，由管理员人工修正。
            }
        }
        return styleKnowledgeService.createDraftFromMaterial(
                source.styleId(),
                source.title() + " - " + material.title(),
                inferScene(source, material),
                inferTargetStyle(source),
                inferProblems(material),
                inferStrategy(material),
                inferParamRanges(material),
                buildKnowledgeContent(source, material)
        );
    }

    private void persistSource(KnowledgeSource source) {
        store.knowledgeSources.put(source.id(), source);
        snapshotRepository.save("knowledge_source", source.id(), source);
        catalogRepository.saveSource(source);
    }

    private void persistMaterial(KnowledgeMaterial material) {
        store.knowledgeMaterials.put(material.id(), material);
        snapshotRepository.save("knowledge_material", material.id(), material);
        catalogRepository.saveMaterial(material);
    }

    private KnowledgeSource getSource(Long sourceId) {
        KnowledgeSource source = store.knowledgeSources.get(sourceId);
        if (source == null) {
            throw new NotFoundException("未找到调色素材来源：" + sourceId);
        }
        return source;
    }

    private KnowledgeMaterial getMaterial(Long sourceId, Long materialId) {
        KnowledgeMaterial material = store.knowledgeMaterials.get(materialId);
        if (material == null || !material.sourceId().equals(sourceId)) {
            throw new NotFoundException("未找到调色素材：" + materialId);
        }
        return material;
    }

    private String inferScene(KnowledgeSource source, KnowledgeMaterial material) {
        String text = source.title() + " " + material.content();
        if (text.contains("夜景")) return "夜景";
        if (text.contains("人像")) return "人像";
        if (text.contains("风光") || text.contains("城市")) return "风光";
        return "通用摄影场景";
    }

    private String inferTargetStyle(KnowledgeSource source) {
        if (source.title().contains("电影")) return "电影感";
        if (source.title().contains("日系")) return "日系清透";
        return source.title();
    }

    private List<String> inferProblems(KnowledgeMaterial material) {
        String content = material.content();
        if (content.contains("高光") || content.contains("阴影")) {
            return List.of("高光与暗部层次需要控制");
        }
        if (content.contains("白平衡") || content.contains("色温")) {
            return List.of("色温与色偏需要精细控制");
        }
        return List.of("需要从素材中复核适用场景与参数边界");
    }

    private List<String> inferStrategy(KnowledgeMaterial material) {
        return List.of(
                "保留素材原始描述，进入人工审核后再启用",
                "优先提炼全局调色参数，局部遮罩类技巧单独标注",
                "应用到用户照片前需要结合照片内容和当前 Lightroom 参数二次判断"
        );
    }

    private Map<String, String> inferParamRanges(KnowledgeMaterial material) {
        String content = material.content();
        if (content.contains("高光") || content.contains("阴影")) {
            return Map.of(
                    "highlights", "按照片高光溢出程度降低",
                    "shadows", "按暗部细节适度提升"
            );
        }
        return Map.of();
    }

    private String buildUploadedVideoNotes(String notes, StoredFile storedFile) {
        String prefix = "上传文件：" + storedFile.fileName() + "\n存储地址：" + storedFile.fileUrl();
        String cleaned = trimOrDefault(notes, "");
        return cleaned.isBlank() ? prefix : prefix + "\n备注：" + cleaned;
    }

    private String buildKnowledgeContent(KnowledgeSource source, KnowledgeMaterial material) {
        return """
                来源类型：%s
                来源标题：%s
                作者：%s
                原始链接：%s
                素材类型：%s
                素材标题：%s
                素材语言：%s

                素材内容：
                %s
                """.formatted(
                source.sourceType(), source.title(), source.author(), source.originalUrl(),
                material.materialType(), material.title(), material.language(), material.content()
        ).trim();
    }

    private String buildSourceSummary(KnowledgeSource source) {
        return """
                来源类型：%s
                来源标题：%s
                作者：%s
                原始链接：%s
                备注：%s
                """.formatted(source.sourceType(), source.title(), source.author(), source.originalUrl(), source.notes()).trim();
    }

    private String extractOriginalUrl(String value) {
        String cleaned = trimOrDefault(value, "");
        int start = cleaned.indexOf("http://");
        if (start < 0) start = cleaned.indexOf("https://");
        if (start < 0) return cleaned;
        int end = cleaned.indexOf(' ', start);
        String url = end < 0 ? cleaned.substring(start) : cleaned.substring(start, end);
        return url.replaceAll("[，,。！？!]+$", "");
    }

    private String trimOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
