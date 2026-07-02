package com.tonepilot.starter.agent;

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
import com.tonepilot.starter.*;





import com.tonepilot.infrastructure.admin.AdminRuntimeClient;
import com.tonepilot.application.lightroom.LightroomStateService;
import com.tonepilot.application.lightroom.LightroomToolService;
import com.tonepilot.application.config.RuntimeConfigService;
import com.tonepilot.infrastructure.observability.RuntimeTraceLogger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentOrchestratorTest {

    @Test
    void returnsImmediatelyWhenLightroomPluginHeartbeatIsMissing() {
        TestContext context = new TestContext();
        when(context.stateService.status()).thenReturn(Map.of(
                "available", false,
                "message", "TonePilot Local Runtime 已启动，但 Lightroom 插件尚未写入心跳。"
        ));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "夜景电影感",
                "provider", "qwen2",
                "sessionId", "session-1"
        ));

        assertThat(result).containsEntry("success", false);
        assertThat(String.valueOf(result.get("message"))).contains("Lightroom");
        verify(context.toolService, never()).applyAdjustments(anyMap(), any());
        verify(context.traceLogger).warn(eq("agent.lightroom.unavailable"), eq("session-1"), anyMap());
    }

    @Test
    void analysisOnlyRequestRespondsWithoutApplyingLightroomSettings() {
        TestContext context = new TestContext();
        context.lightroomAvailable();
        context.qwenSelected();
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "这张照片是夜景城市照片，灯光层次可以作为主要表现点。",
                Map.of(),
                List.of(),
                Map.of("intent", "分析照片", "photoType", "夜景城市照片", "recommendedStyle", "先说明可调方向"),
                "{\"assistantMessage\":\"这张照片是夜景城市照片\"}"
        ));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "帮我分析下这张图",
                "provider", "qwen2",
                "sessionId", "session-2"
        ));

        assertThat(result).containsEntry("success", true);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).containsEntry("action", "respond");
        assertThat(data).containsKey("modelRawContent");
        verify(context.toolService, never()).applyAdjustments(anyMap(), any());
    }

    @Test
    void explicitEditingRequestReturnsPendingConfirmationWithoutApplyingLightroomSettings() {
        TestContext context = new TestContext();
        context.lightroomAvailable();
        context.qwenSelected();
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "我会压一点高光、提亮暗部，并增强夜景电影感。",
                Map.of("Exposure2012", 0.2),
                List.of(new AgentDelta("basic", "Exposure2012", "曝光", 0, 0.2, 0.2, "提亮画面")),
                Map.of("intent", "修成夜景电影感", "photoType", "夜景", "recommendedStyle", "夜景电影感"),
                "{\"assistantMessage\":\"我会压一点高光\"}"
        ));
        when(context.toolService.applyAdjustments(anyMap(), any())).thenReturn(Map.of(
                "success", true,
                "pending", true,
                "jobId", "agent-apply-1",
                "message", "已提交 Lightroom 调色任务。"
        ));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "修成夜景电影感，再亮一点",
                "provider", "qwen2",
                "sessionId", "session-3"
        ));

        assertThat(result).containsEntry("success", true);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).containsEntry("action", "pending_confirmation");
        assertThat((Map<String, Object>) data.get("pendingApply")).containsEntry("status", "waiting_user_confirmation");
        verify(context.toolService, never()).applyAdjustments(anyMap(), any());
    }

    @Test
    void editingRequestUsesImmutableBaselinePreviewAsBeforeImage() {
        TestContext context = new TestContext();
        context.lightroomAvailableWithBaselinePreview();
        context.qwenSelected();
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "apply",
                Map.of("Exposure2012", 0.2),
                List.of(new AgentDelta("basic", "Exposure2012", "Exposure", 0, 0.2, 0.2, "test")),
                Map.of("intent", "edit", "photoType", "night", "recommendedStyle", "film")
        ));
        when(context.toolService.applyAdjustments(anyMap(), any())).thenReturn(Map.of(
                "success", true,
                "pending", true,
                "jobId", "agent-apply-baseline"
        ));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "edit this photo",
                "provider", "qwen2",
                "sessionId", "session-before"
        ));

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).containsEntry("beforePreviewUrl", "/files/selected-before-DSCF1709-1782890000.jpg?t=1782890000");
    }
    @Test
    void confirmApplySubmitsLastPendingPlanToLightroom() {
        TestContext context = new TestContext();
        context.lightroomAvailableWithBaselinePreview();
        context.qwenSelected();
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "apply",
                Map.of("Exposure2012", 0.2),
                List.of(new AgentDelta("basic", "Exposure2012", "Exposure", 0, 0.2, 0.2, "test")),
                Map.of("intent", "edit", "photoType", "night", "recommendedStyle", "film")
        ));
        when(context.toolService.applyAdjustments(anyMap(), any())).thenReturn(Map.of(
                "success", true,
                "pending", true,
                "jobId", "agent-apply-confirmed"
        ));

        context.orchestrator.chat(Map.of(
                "message", "edit this photo",
                "provider", "qwen2",
                "sessionId", "session-confirm"
        ));
        Map<String, Object> result = context.orchestrator.confirmApply(Map.of("sessionId", "session-confirm"));

        assertThat(result).containsEntry("success", true);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).containsEntry("action", "tool_submitted");
        assertThat(data).containsEntry("beforePreviewUrl", "/files/selected-before-DSCF1709-1782890000.jpg?t=1782890000");
        assertThat((Map<String, Object>) data.get("apply")).containsEntry("jobId", "agent-apply-confirmed");
        verify(context.toolService).applyAdjustments(Map.of("Exposure2012", 0.2), List.of());
    }
    @Test
    void localAdjustmentPlansAreSubmittedToLightroomAsGuidedMaskTasks() {
        TestContext context = new TestContext();
        context.lightroomAvailable();
        context.qwenSelected();
        Map<String, Object> skyPlan = Map.of(
                "type", "linear_gradient",
                "target", "天空",
                "coordinateSpace", "normalized_crop",
                "region", Map.of("x", 0.0, "y", 0.0, "w", 1.0, "h", 0.42),
                "settings", Map.of("Exposure2012", -0.25, "Highlights2012", -18)
        );
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "我会用线性渐变压暗天空，并把这一步下发给 Lightroom 局部工具。",
                new AgentThought(
                        "天空需要单独压暗",
                        List.of("天空占画面上半部分", "用户明确要求天空压暗"),
                        "局部调整比全局压暗更适合保留城市灯光。",
                        "apply_local_adjustments",
                        "调用 Lightroom 局部蒙版引导工具",
                        List.of("打开线性渐变工具", "设置局部曝光和高光参数"),
                        List.of("继续微调天空")
                ),
                Map.of(),
                List.of(),
                Map.of("intent", "天空压暗", "photoType", "夜景城市照片", "recommendedStyle", "局部压暗天空"),
                List.of(skyPlan),
                "{\"localAdjustments\":[{\"target\":\"天空\"}]}"
        ));
        when(context.toolService.applyAdjustments(anyMap(), any())).thenReturn(Map.of(
                "success", true,
                "pending", true,
                "jobId", "agent-apply-local-1"
        ));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "天空单独压暗一点",
                "provider", "qwen2",
                "sessionId", "session-local"
        ));

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertThat(data).containsEntry("action", "pending_confirmation");
        assertThat((List<Map<String, Object>>) data.get("localAdjustments")).containsExactly(skyPlan);
        assertThat((Map<String, Object>) data.get("capabilities")).containsEntry("supportsLocalMaskGuides", true);
        verify(context.toolService, never()).applyAdjustments(anyMap(), any());
    }

    @Test
    void modelFailureReturnsUserVisibleMessageWithoutRuleFallback() {
        TestContext context = new TestContext();
        context.lightroomAvailable();
        context.qwenSelected();
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString()))
                .thenThrow(new IllegalStateException("模型配置不完整，请先填写 API Key。"));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "帮我修图",
                "provider", "qwen2",
                "sessionId", "session-model-error"
        ));

        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).isEqualTo("模型配置不完整，请先填写 API Key。");
        verify(context.toolService, never()).applyAdjustments(anyMap(), any());
    }

    @Test
    void passesAdminKnowledgeMatchesIntoModelInput() {
        TestContext context = new TestContext();
        context.lightroomAvailable();
        context.qwenSelected();
        when(context.adminRuntimeClient.retrieveKnowledge("夜景电影感", 5)).thenReturn(List.of(Map.of(
                "title", "夜景高光控制",
                "content", "夜景照片先压高光，再提阴影保护灯光层次。",
                "score", 0.92
        )));
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "我会参考夜景高光控制知识给出建议。",
                Map.of(),
                List.of(),
                Map.of("intent", "夜景电影感", "photoType", "夜景", "recommendedStyle", "压高光提阴影")
        ));

        context.orchestrator.chat(Map.of(
                "message", "夜景电影感",
                "provider", "qwen2",
                "sessionId", "session-knowledge"
        ));

        var captor = forClass(AgentInput.class);
        verify(context.modelAgent).plan(captor.capture(), eq("qwen2"), anyMap(), anyString());
        assertThat(captor.getValue().knowledgeMatches()).hasSize(1);
        assertThat(captor.getValue().knowledgeMatches().get(0).get("title")).isEqualTo("夜景高光控制");
        assertThat(captor.getValue().photoMetadata()).containsEntry("fileName", "DSCF1709.RAF");
        assertThat(captor.getValue().previewUrl()).contains("selected-preview.jpg");
    }



    @Test
    void chatReturnsObservableReactEventsForAgentRun() {
        TestContext context = new TestContext();
        context.lightroomAvailable();
        context.qwenSelected();
        when(context.modelAgent.plan(any(), eq("qwen2"), anyMap(), anyString())).thenReturn(new AgentTuneResult(
                "我会先说明夜景照片的调色方向。",
                new AgentThought(
                        "判断为夜景城市照片",
                        List.of("当前选中 Lightroom 照片", "用户要求分析照片"),
                        "先观察照片和知识库，再决定本轮只回复分析。",
                        "respond",
                        "等待用户确认是否修图",
                        List.of("不调用 Lightroom 工具"),
                        List.of("确认按这个方向修")
                ),
                Map.of(),
                List.of(),
                Map.of("intent", "分析照片", "photoType", "夜景城市照片", "recommendedStyle", "先给方向"),
                List.of(),
                "{\"assistantMessage\":\"我会先说明夜景照片的调色方向。\"}"
        ));

        Map<String, Object> result = context.orchestrator.chat(Map.of(
                "message", "帮我分析这张图",
                "provider", "qwen2",
                "sessionId", "session-react"
        ));

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> events = (List<Map<String, Object>>) data.get("reactEvents");
        assertThat(events).extracting(event -> event.get("type"))
                .containsSubsequence(
                        "agent.started",
                        "agent.observation",
                        "knowledge.retrieved",
                        "model.request",
                        "agent.thought",
                        "agent.final"
                );
    }

    private static class TestContext {
        private final LightroomStateService stateService = mock(LightroomStateService.class);
        private final LightroomToolService toolService = mock(LightroomToolService.class);
        private final ModelRuntimeAgent modelAgent = mock(ModelRuntimeAgent.class);
        private final RuntimeConfigService configService = mock(RuntimeConfigService.class);
        private final AdminRuntimeClient adminRuntimeClient = mock(AdminRuntimeClient.class);
        private final RuntimeTraceLogger traceLogger = mock(RuntimeTraceLogger.class);
        private final RuntimeAgentOrchestrator orchestrator = new RuntimeAgentOrchestrator();

        TestContext() {
            ReflectionTestUtils.setField(orchestrator, "stateService", stateService);
            ReflectionTestUtils.setField(orchestrator, "toolService", toolService);
            ReflectionTestUtils.setField(orchestrator, "modelAgent", modelAgent);
            ReflectionTestUtils.setField(orchestrator, "configService", configService);
            ReflectionTestUtils.setField(orchestrator, "adminRuntimeClient", adminRuntimeClient);
            ReflectionTestUtils.setField(orchestrator, "traceLogger", traceLogger);
        }

        void lightroomAvailable() {
            when(stateService.status()).thenReturn(Map.of("available", true));
            when(stateService.selectedPhoto()).thenReturn(Map.of(
                    "available", true,
                    "photo", Map.of("fileName", "DSCF1709.RAF"),
                    "currentAdjustment", Map.of(),
                    "previewUrl", "/files/selected-preview.jpg?t=1782890000"
            ));
        }

        void lightroomAvailableWithBaselinePreview() {
            when(stateService.status()).thenReturn(Map.of("available", true));
            when(stateService.selectedPhoto()).thenReturn(Map.of(
                    "available", true,
                    "photo", Map.of("fileName", "DSCF1709.RAF"),
                    "currentAdjustment", Map.of(),
                    "previewUrl", "/files/selected-preview.jpg?t=1782890001",
                    "baselinePreviewUrl", "/files/selected-before-DSCF1709-1782890000.jpg?t=1782890000"
            ));
        }
        void qwenSelected() {
            when(configService.readInternalConfig()).thenReturn(Map.of(
                    "provider", "qwen2",
                    "qwen2", Map.of("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1", "model", "qwen-plus", "apiKey", "test")
            ));
        }
    }
}
