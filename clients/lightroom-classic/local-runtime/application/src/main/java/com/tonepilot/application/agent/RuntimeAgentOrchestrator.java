package com.tonepilot.application.agent;

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





import com.tonepilot.infrastructure.admin.AdminRuntimeClient;
import com.tonepilot.application.lightroom.LightroomStateService;
import com.tonepilot.application.lightroom.LightroomToolService;
import com.tonepilot.application.config.RuntimeConfigService;
import com.tonepilot.infrastructure.observability.RuntimeTraceLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class RuntimeAgentOrchestrator {

    @Autowired
    private LightroomStateService stateService;

    @Autowired
    private LightroomToolService toolService;

    @Autowired
    private ModelRuntimeAgent modelAgent;

    @Autowired
    private RuntimeConfigService configService;

    @Autowired
    private AdminRuntimeClient adminRuntimeClient;

    @Autowired
    private RuntimeTraceLogger traceLogger;

    private final Map<String, PendingApplyPlan> pendingApplyPlans = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(Map<String, Object> payload) {
        return chat(payload, event -> {
        });
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(Map<String, Object> payload, Consumer<AgentReactEvent> eventSink) {
        List<AgentReactEvent> reactEvents = new ArrayList<>();
        String sessionId = sessionId(payload);
        String message = String.valueOf(payload.getOrDefault("message", "")).trim();
        String requestedProvider = String.valueOf(payload.getOrDefault("provider", ""));
        emit(reactEvents, eventSink, sessionId, "agent.started", "开始处理请求", "我已收到你的修图/分析请求，准备进入 ReAct 判断。", Map.of(
                "messageLength", message.length(),
                "requestedProvider", requestedProvider
        ));
        traceLogger.info("agent.request.received", sessionId, Map.of(
                "messageLength", message.length(),
                "requestedProvider", requestedProvider
        ));

        if (message.isBlank()) {
            emit(reactEvents, eventSink, sessionId, "agent.error", "请求被拒绝", "请输入调色或分析指令。", Map.of("reason", "empty_message"));
            traceLogger.warn("agent.request.rejected", sessionId, Map.of("reason", "empty_message"));
            return withEvents(Map.of("success", false, "message", "请输入调色或分析指令。"), reactEvents);
        }

        Map<String, Object> status = stateService.status();
        emit(reactEvents, eventSink, sessionId, "agent.observation", "观察 Lightroom 状态", "我正在确认 Lightroom 插件是否可用。", Map.of("status", status));
        if (!Boolean.TRUE.equals(status.get("available"))) {
            emit(reactEvents, eventSink, sessionId, "agent.error", "Lightroom 不可用", String.valueOf(status.getOrDefault("message", "请确认 Lightroom 插件正在运行并写入心跳。")), status);
            traceLogger.warn("agent.lightroom.unavailable", sessionId, status);
            return withEvents(Map.of(
                    "success", false,
                    "message", "Lightroom 插件不可用：" + status.getOrDefault("message", "请确认 Lightroom 插件正在运行并写入心跳。")
            ), reactEvents);
        }

        Map<String, Object> selected = stateService.selectedPhoto();
        emit(reactEvents, eventSink, sessionId, "agent.observation", "读取当前照片", "我正在读取 Lightroom 当前选中照片、元数据和当前调色参数。", Map.of("selected", selected));
        if (!Boolean.TRUE.equals(selected.get("available"))) {
            emit(reactEvents, eventSink, sessionId, "agent.error", "当前没有可用照片", String.valueOf(selected.getOrDefault("message", "Lightroom 当前没有选中照片。")), selected);
            traceLogger.warn("agent.photo.unavailable", sessionId, selected);
            return withEvents(Map.of("success", false, "message", selected.getOrDefault("message", "Lightroom 当前没有选中照片。")), reactEvents);
        }

        Map<String, Object> currentAdjustment = selected.get("currentAdjustment") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Map<String, Object> runtimeConfig = configService.readInternalConfig();
        String provider = String.valueOf(payload.getOrDefault("provider", runtimeConfig.getOrDefault("provider", "qwen2")));
        emit(reactEvents, eventSink, sessionId, "agent.observation", "选择模型", "我会使用当前选择的模型来完成本轮判断。", Map.of("provider", provider));
        traceLogger.info("agent.provider.selected", sessionId, Map.of("provider", provider));

        List<Map<String, Object>> knowledgeMatches = knowledgeMatches(message, runtimeConfig);
        emit(reactEvents, eventSink, sessionId, "knowledge.retrieved", "检索知识库", knowledgeMatches.isEmpty()
                ? "没有检索到可用知识，我会只基于当前照片和对话上下文判断。"
                : "我已检索到可参考的调色知识，会把它纳入本轮判断。", Map.of("matchCount", knowledgeMatches.size(), "matches", knowledgeMatches));
        traceLogger.info("agent.knowledge.retrieved", sessionId, Map.of("matchCount", knowledgeMatches.size()));

        Map<String, Object> photoMetadata = selected.get("photo") instanceof Map<?, ?> photoMap
                ? (Map<String, Object>) photoMap
                : Map.of();
        String previewUrl = String.valueOf(selected.getOrDefault("previewUrl", selected.getOrDefault("baselinePreviewUrl", "")));
        AgentInput agentInput = new AgentInput(message, currentAdjustment, knowledgeMatches, photoMetadata, previewUrl);
        AgentTuneResult tuneResult;
        try {
            emit(reactEvents, eventSink, sessionId, "model.request", "调用主 Agent 模型", "我正在让主 Agent 结合意图、照片参数和知识库生成下一步决策。", Map.of("provider", provider));
            tuneResult = modelAgent.plan(agentInput, provider, runtimeConfig, sessionId);
            emit(reactEvents, eventSink, sessionId, "model.response", "模型返回完成", "主 Agent 已返回可展示的判断结果。", Map.of(
                    "hasDevelopSettings", tuneResult.developSettings() != null && !tuneResult.developSettings().isEmpty(),
                    "settingCount", tuneResult.developSettings() == null ? 0 : tuneResult.developSettings().size(),
                    "localAdjustmentCount", tuneResult.localAdjustments() == null ? 0 : tuneResult.localAdjustments().size()
            ));
        } catch (Exception exception) {
            emit(reactEvents, eventSink, sessionId, "agent.error", "模型调用失败", exception.getMessage(), Map.of("provider", provider, "error", exception.getMessage()));
            traceLogger.warn("agent.model.failed", sessionId, Map.of(
                    "provider", provider,
                    "error", exception.getMessage()
            ));
            return withEvents(Map.of("success", false, "message", exception.getMessage()), reactEvents);
        }
        emit(reactEvents, eventSink, sessionId, "agent.thought", "主 Agent 判断", visibleThoughtContent(tuneResult), Map.of(
                "agentThought", tuneResult.agentThought(),
                "analysis", tuneResult.analysis(),
                "deltas", tuneResult.deltas(),
                "developSettings", tuneResult.developSettings(),
                "localAdjustments", tuneResult.localAdjustments()
        ));
        traceLogger.info("agent.intent.analyzed", sessionId, Map.of(
                "deltaCount", tuneResult.deltas().size(),
                "settingCount", tuneResult.developSettings().size(),
                "localAdjustmentCount", tuneResult.localAdjustments().size(),
                "analysis", tuneResult.analysis()
        ));

        Map<String, Object> data = baseData(sessionId, selected, tuneResult);
        data.put("beforePreviewUrl", String.valueOf(selected.getOrDefault("baselinePreviewUrl", selected.getOrDefault("previewUrl", ""))));
        data.put("knowledgeMatches", knowledgeMatches);

        if (shouldApply(message, tuneResult)) {
            PendingApplyPlan pendingPlan = new PendingApplyPlan(
                    sessionId,
                    data,
                    tuneResult.developSettings() == null ? Map.of() : new LinkedHashMap<>(tuneResult.developSettings()),
                    tuneResult.localAdjustments() == null ? List.of() : new ArrayList<>(tuneResult.localAdjustments())
            );
            pendingApplyPlans.put(sessionId, pendingPlan);
            data.put("action", "pending_confirmation");
            data.put("pendingApply", Map.of(
                    "status", "waiting_user_confirmation",
                    "sessionId", sessionId,
                    "settingCount", pendingPlan.developSettings().size(),
                    "localAdjustmentCount", pendingPlan.localAdjustments().size()
            ));
            data.put("assistantMessage", mergeMessage(
                    tuneResult.assistantMessage(),
                    "我已经整理好这次要写入 Lightroom 的调色方案。请先检查上面的参数和局部蒙版计划，确认后再点“确认应用到 Lightroom”。"
            ));
            emit(reactEvents, eventSink, sessionId, "agent.final", "等待用户确认", String.valueOf(data.get("assistantMessage")), Map.of("data", data));
            data.put("reactEvents", reactEvents.stream().map(AgentReactEvent::toMap).toList());
            adminRuntimeClient.recordEvent("agent.apply.waiting_confirmation", sessionId, data);
            traceLogger.info("agent.apply.waiting_confirmation", sessionId, Map.of("pendingApply", data.get("pendingApply")));
            return Map.of("success", true, "message", data.get("assistantMessage"), "data", data);
        }

        data.put("action", "respond");
        data.put("assistantMessage", tuneResult.assistantMessage());
        emit(reactEvents, eventSink, sessionId, "agent.final", "本轮完成", tuneResult.assistantMessage(), Map.of("data", data));
        data.put("reactEvents", reactEvents.stream().map(AgentReactEvent::toMap).toList());
        adminRuntimeClient.recordEvent("agent.message.responded", sessionId, data);
        traceLogger.info("agent.response.ready", sessionId, Map.of("action", "respond"));
        return Map.of("success", true, "message", data.get("assistantMessage"), "data", data);
    }

    public Map<String, Object> confirmApply(Map<String, Object> payload) {
        String sessionId = sessionId(payload);
        PendingApplyPlan pendingPlan = pendingApplyPlans.get(sessionId);
        if (pendingPlan == null) {
            traceLogger.warn("agent.apply.confirm_missing", sessionId, Map.of("reason", "no_pending_plan"));
            return Map.of("success", false, "message", "没有找到待确认的调色方案，请先让 Agent 生成一版方案。", "data", Map.of("action", "confirm_missing", "sessionId", sessionId));
        }
        Map<String, Object> status = stateService.status();
        if (!Boolean.TRUE.equals(status.get("available"))) {
            traceLogger.warn("agent.apply.confirm_lightroom_unavailable", sessionId, status);
            return Map.of("success", false, "message", "Lightroom 插件不可用：" + status.getOrDefault("message", "请确认 Lightroom 插件正在运行并写入心跳。"), "data", Map.of("action", "confirm_failed", "sessionId", sessionId));
        }

        Map<String, Object> applyResult = toolService.applyAdjustments(pendingPlan.developSettings(), pendingPlan.localAdjustments());
        Map<String, Object> data = new LinkedHashMap<>(pendingPlan.data());
        data.put("action", "tool_submitted");
        data.put("apply", applyResult);
        data.put("afterPreviewUrl", "");
        data.put("assistantMessage", "已确认应用到 Lightroom，正在等待插件完成调色并刷新修图后预览。");
        pendingApplyPlans.remove(sessionId);
        adminRuntimeClient.recordEvent("agent.tool.submitted", sessionId, data);
        traceLogger.info("agent.tool.submitted", sessionId, Map.of("apply", applyResult));
        return Map.of("success", applyResult.getOrDefault("success", true), "message", data.get("assistantMessage"), "data", data);
    }


    public Map<String, Object> applyStatus(String jobId) {
        return toolService.applyStatus(jobId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withEvents(Map<String, Object> result, List<AgentReactEvent> reactEvents) {
        Map<String, Object> value = new LinkedHashMap<>(result);
        value.put("reactEvents", reactEvents.stream().map(AgentReactEvent::toMap).toList());
        if (value.get("data") instanceof Map<?, ?> dataMap) {
            Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) dataMap);
            data.put("reactEvents", reactEvents.stream().map(AgentReactEvent::toMap).toList());
            value.put("data", data);
        }
        return value;
    }

    private void emit(
            List<AgentReactEvent> events,
            Consumer<AgentReactEvent> sink,
            String sessionId,
            String type,
            String title,
            String content,
            Map<String, Object> payload
    ) {
        AgentReactEvent event = AgentReactEvent.of(type, title, content, payload);
        events.add(event);
        if (sink != null) {
            sink.accept(event);
        }
        traceLogger.info(type, sessionId, event.toMap());
        adminRuntimeClient.recordEvent(type, sessionId, event.toMap());
    }

    private String visibleThoughtContent(AgentTuneResult tuneResult) {
        AgentThought thought = tuneResult.agentThought();
        if (thought != null && thought.summary() != null && !thought.summary().isBlank()) {
            StringBuilder builder = new StringBuilder(thought.summary());
            if (thought.reasoningVisible() != null && !thought.reasoningVisible().isBlank()) {
                builder.append("\n").append(thought.reasoningVisible());
            }
            if (thought.nextAction() != null && !thought.nextAction().isBlank()) {
                builder.append("\n下一步：").append(thought.nextAction());
            }
            return builder.toString();
        }
        Map<String, Object> analysis = tuneResult.analysis() == null ? Map.of() : tuneResult.analysis();
        return String.valueOf(analysis.getOrDefault("recommendedStyle", tuneResult.assistantMessage()));
    }

    private boolean shouldApply(String message, AgentTuneResult tuneResult) {
        boolean hasDevelopSettings = tuneResult.developSettings() != null && !tuneResult.developSettings().isEmpty();
        boolean hasLocalAdjustments = tuneResult.localAdjustments() != null && !tuneResult.localAdjustments().isEmpty();
        if (!hasDevelopSettings && !hasLocalAdjustments) {
            return false;
        }
        String decision = tuneResult.agentThought() == null ? "" : String.valueOf(tuneResult.agentThought().decision()).trim();
        if ("apply_global_adjustments".equals(decision) || "apply_local_adjustments".equals(decision)) {
            return true;
        }
        String value = message == null ? "" : message.trim().toLowerCase();
        if ("plan_local_adjustments".equals(decision)) {
            return containsAny(value, "修", "调", "改", "应用", "执行", "按这个", "压暗", "提亮", "单独");
        }
        if (!decision.isBlank()) {
            return false;
        }
        if (containsAny(value, "不要修", "先别修", "只分析", "分析一下", "看看", "建议", "方案")) {
            return containsAny(value, "修成", "调成", "改成", "执行", "应用", "按这个修");
        }
        return containsAny(value,
                "修", "调", "改", "应用", "执行", "亮", "暗", "冷", "暖", "电影", "胶片",
                "通透", "干净", "鲜艳", "饱和", "对比", "肤色", "绿色", "蓝色", "局部", "蒙版", "天空", "主体", "apply", "edit");
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> baseData(String sessionId, Map<String, Object> selected, AgentTuneResult tuneResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("photo", selected.getOrDefault("photo", Map.of()));
        data.put("analysis", tuneResult.analysis());
        data.put("agentThought", tuneResult.agentThought());
        data.put("assistantMessage", tuneResult.assistantMessage());
        data.put("deltas", tuneResult.deltas());
        data.put("developSettings", tuneResult.developSettings());
        data.put("localAdjustments", tuneResult.localAdjustments());
        data.put("suggestedReplies", tuneResult.agentThought().userOptions() == null ? List.of() : tuneResult.agentThought().userOptions());
        data.put("capabilities", Map.of(
                "supportsGlobalDevelopSettings", true,
                "supportsLocalMasks", false,
                "supportsLocalMaskGuides", true,
                "localMaskMode", "guided_tool",
                "message", "当前 Lightroom 插件可打开对应局部工具并设置参数；现代蒙版区域仍需要在 Lightroom 中确认或拖拽。"
        ));
        data.put("modelRawContent", tuneResult.rawModelContent());
        return data;
    }

    private List<Map<String, Object>> knowledgeMatches(String message, Map<String, Object> runtimeConfig) {
        Object knowledge = runtimeConfig.get("knowledge");
        boolean enabled = knowledge instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("enabled"));
        List<Map<String, Object>> remoteMatches = adminRuntimeClient.retrieveKnowledge(message, 5);
        remoteMatches = remoteMatches == null ? List.of() : remoteMatches;
        if (!remoteMatches.isEmpty()) {
            return remoteMatches;
        }
        if (!enabled) {
            return List.of(Map.of(
                    "title", "本地知识库未启用",
                    "summary", "当前仅基于照片状态、上下文和模型判断生成调色方向。"
            ));
        }
        return List.of(Map.of(
                "title", "知识库检索占位",
                "summary", "已启用知识库配置，后续可接入管理端素材检索结果。"
        ));
    }

    private String mergeMessage(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return first + "\n\n" + second;
    }

    private record PendingApplyPlan(
            String sessionId,
            Map<String, Object> data,
            Map<String, Object> developSettings,
            List<Map<String, Object>> localAdjustments
    ) {
    }


    private String sessionId(Map<String, Object> payload) {
        Object value = payload.get("sessionId");
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return "local-session-" + System.currentTimeMillis();
        }
        return String.valueOf(value);
    }
}
