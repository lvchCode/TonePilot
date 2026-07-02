package com.tonepilot.application.controller;

import com.tonepilot.application.agent.*;
import com.tonepilot.application.config.*;
import com.tonepilot.application.controller.*;
import com.tonepilot.application.lightroom.*;
import com.tonepilot.domain.agent.*;
import com.tonepilot.repository.lightroom.*;
import com.tonepilot.infrastructure.admin.*;
import com.tonepilot.infrastructure.config.*;
import com.tonepilot.infrastructure.lightroom.filesystem.*;
import com.tonepilot.infrastructure.lightroom.repository.*;
import com.tonepilot.infrastructure.model.*;
import com.tonepilot.infrastructure.observability.*;





import com.tonepilot.application.agent.RuntimeAgentOrchestrator;
import com.tonepilot.application.lightroom.LightroomStateService;
import com.tonepilot.application.config.RuntimeConfigService;
import com.tonepilot.infrastructure.observability.TraceContextManager;
import com.tonepilot.infrastructure.observability.RuntimeTraceLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@CrossOrigin
@RestController
public class LocalRuntimeController {

    @Autowired
    private LightroomStateService stateService;

    @Autowired
    private RuntimeConfigService configService;

    @Autowired
    private RuntimeAgentOrchestrator orchestrator;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private RuntimeTraceLogger traceLogger;

    @Autowired
    private TraceContextManager traceContextManager;

    @GetMapping("/status")
    public Map<String, Object> status() {
        traceLogger.info("api.status.request", "", Map.of());
        return stateService.status();
    }

    @GetMapping("/api/lightroom/selected-photo")
    public Map<String, Object> selectedPhoto() {
        traceLogger.info("api.selected_photo.request", "", Map.of());
        return stateService.selectedPhoto();
    }

    @GetMapping("/api/runtime/config")
    public Map<String, Object> runtimeConfig() {
        traceLogger.info("api.runtime_config.read_request", "", Map.of());
        return configService.readPublicConfig();
    }

    @PostMapping("/api/runtime/config")
    public Map<String, Object> saveRuntimeConfig(@RequestBody Map<String, Object> patch) {
        traceLogger.info("api.runtime_config.write_request", "", Map.of("keys", patch == null ? java.util.List.of() : patch.keySet()));
        return configService.writeConfig(patch);
    }

    @PostMapping("/api/lightroom-agent/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> payload) {
        String sessionId = String.valueOf(payload.getOrDefault("sessionId", ""));
        try (TraceContextManager.TraceScope ignored = traceContextManager.open(sessionId)) {
            traceLogger.info("api.agent_chat.request", sessionId, Map.of(
                    "provider", payload.getOrDefault("provider", ""),
                    "messageLength", String.valueOf(payload.getOrDefault("message", "")).length()
            ));
            return orchestrator.chat(payload);
        }
    }

    @PostMapping(value = "/api/lightroom-agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> payload) {
        String sessionId = String.valueOf(payload.getOrDefault("sessionId", ""));
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try (TraceContextManager.TraceScope ignored = traceContextManager.open(sessionId)) {
                traceLogger.info("api.agent_chat_stream.request", sessionId, Map.of(
                        "provider", payload.getOrDefault("provider", ""),
                        "messageLength", String.valueOf(payload.getOrDefault("message", "")).length()
                ));
                orchestrator.chat(payload, event -> sendEvent(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    sendEvent(emitter, AgentReactEvent.of(
                            "agent.error",
                            "流式请求失败",
                            exception.getMessage(),
                            Map.of("error", exception.getMessage())
                    ));
                } catch (Exception ignored) {
                    // SSE 客户端断开时无需继续处理。
                }
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, AgentReactEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(event.toMap(), MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new IllegalStateException("发送 Agent 流式事件失败：" + exception.getMessage(), exception);
        }
    }

    @PostMapping("/api/lightroom-agent/apply-confirmed")
    public Map<String, Object> applyConfirmed(@RequestBody Map<String, Object> payload) {
        String sessionId = String.valueOf(payload.getOrDefault("sessionId", ""));
        try (TraceContextManager.TraceScope ignored = traceContextManager.open(sessionId)) {
            traceLogger.info("api.agent_apply_confirmed.request", sessionId, Map.of("sessionId", sessionId));
            return orchestrator.confirmApply(payload);
        }
    }


    @GetMapping("/api/lightroom-agent/apply-status/{jobId}")
    public Map<String, Object> applyStatus(@PathVariable String jobId) {
        traceLogger.info("api.agent_apply_status.request", jobId, Map.of("jobId", jobId));
        return orchestrator.applyStatus(jobId);
    }

    @GetMapping("/agent-console")
    public ResponseEntity<String> agentConsole() {
        traceLogger.info("api.agent_console.request", "", Map.of());
        try {
            var resource = resourceLoader.getResource("classpath:static/agent-console.html");
            try (var input = resource.getInputStream()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            traceLogger.error("api.agent_console.failed", "", Map.of("error", error.getMessage()));
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Agent 控制台加载失败：" + error.getMessage());
        }
    }

    @GetMapping("/files/{fileName}")
    public ResponseEntity<FileSystemResource> file(@PathVariable String fileName) {
        traceLogger.info("api.file.request", "", Map.of("fileName", fileName));
        var path = stateService.resultFile(fileName);
        if (!Files.exists(path)) {
            traceLogger.warn("api.file.not_found", "", Map.of("fileName", fileName, "path", path.toString()));
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(path));
    }
}
