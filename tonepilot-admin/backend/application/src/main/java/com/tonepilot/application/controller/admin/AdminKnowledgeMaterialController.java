package com.tonepilot.application.controller.admin;

import com.tonepilot.application.dto.ApiResponse;
import com.tonepilot.application.dto.KnowledgeMaterialRequest;
import com.tonepilot.application.dto.KnowledgeSourceRequest;
import com.tonepilot.application.knowledge.KnowledgeMaterialIngestionService;
import com.tonepilot.domain.knowledge.DouyinImportRequest;
import com.tonepilot.domain.knowledge.KnowledgeExtractionJob;
import com.tonepilot.domain.knowledge.KnowledgeMaterial;
import com.tonepilot.domain.knowledge.KnowledgeSource;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@CrossOrigin
@RestController
@RequestMapping("/api/admin/knowledge-sources")
public class AdminKnowledgeMaterialController {

    @Autowired
    private KnowledgeMaterialIngestionService ingestionService;

    @GetMapping
    public ApiResponse<List<KnowledgeSource>> listSources() {
        return ApiResponse.ok(ingestionService.listSources());
    }

    @PostMapping
    public ApiResponse<KnowledgeSource> createSource(@Valid @RequestBody KnowledgeSourceRequest request) {
        return ApiResponse.ok(ingestionService.createSource(request));
    }

    @PostMapping("/douyin-imports")
    public ApiResponse<KnowledgeExtractionJob> importDouyinVideo(@Valid @RequestBody DouyinImportRequest request) {
        return ApiResponse.ok(ingestionService.importDouyinVideo(request));
    }

    @PostMapping("/douyin-video-uploads")
    public ApiResponse<KnowledgeExtractionJob> uploadDouyinVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "styleId", required = false) Long styleId,
            @RequestParam(value = "notes", required = false) String notes
    ) {
        return ApiResponse.ok(ingestionService.importUploadedDouyinVideo(file, title, author, styleId, notes));
    }

    @GetMapping("/{sourceId}/materials")
    public ApiResponse<List<KnowledgeMaterial>> listMaterials(@PathVariable Long sourceId) {
        return ApiResponse.ok(ingestionService.listMaterials(sourceId));
    }

    @PostMapping("/{sourceId}/materials")
    public ApiResponse<KnowledgeMaterial> importMaterial(
            @PathVariable Long sourceId,
            @Valid @RequestBody KnowledgeMaterialRequest request
    ) {
        return ApiResponse.ok(ingestionService.importMaterial(sourceId, request));
    }

    @PostMapping("/{sourceId}/materials/{materialId}/extract")
    public ApiResponse<KnowledgeExtractionJob> extractToKnowledge(
            @PathVariable Long sourceId,
            @PathVariable Long materialId
    ) {
        return ApiResponse.ok(ingestionService.extractToKnowledge(sourceId, materialId));
    }
}
