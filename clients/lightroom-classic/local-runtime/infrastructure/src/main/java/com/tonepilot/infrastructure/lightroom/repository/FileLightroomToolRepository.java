package com.tonepilot.infrastructure.lightroom.repository;

import com.tonepilot.domain.agent.*;
import com.tonepilot.repository.lightroom.*;
import com.tonepilot.infrastructure.admin.*;
import com.tonepilot.infrastructure.config.*;
import com.tonepilot.infrastructure.lightroom.filesystem.*;
import com.tonepilot.infrastructure.lightroom.repository.*;
import com.tonepilot.infrastructure.model.*;
import com.tonepilot.infrastructure.observability.*;





import com.tonepilot.infrastructure.observability.RuntimeTraceLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import com.tonepilot.repository.lightroom.LightroomToolRepository;
import com.tonepilot.infrastructure.config.RuntimeProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class FileLightroomToolRepository implements LightroomToolRepository {

    @Autowired
    private RuntimeProperties properties;

    @Autowired
    private RuntimeTraceLogger traceLogger;

    public Map<String, Object> applyDevelopSettings(Map<String, Object> developSettings) {
        return applyAdjustments(developSettings, List.of());
    }

    public Map<String, Object> applyAdjustments(
            Map<String, Object> developSettings,
            List<Map<String, Object>> localAdjustments
    ) {
        Map<String, Object> safeDevelopSettings = developSettings == null ? Map.of() : developSettings;
        List<Map<String, Object>> safeLocalAdjustments = localAdjustments == null ? List.of() : localAdjustments;
        if (safeDevelopSettings.isEmpty() && safeLocalAdjustments.isEmpty()) {
            traceLogger.info("lightroom.apply.skipped", "", Map.of("reason", "empty_adjustments"));
            return Map.of("success", true, "message", "没有需要应用的 Lightroom 参数或局部蒙版计划。");
        }
        String jobId = "agent-apply-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            BridgePaths paths = new BridgePaths(properties);
            Files.createDirectories(paths.fs("apply-jobs"));
            Files.createDirectories(paths.fs("apply-results"));
            Files.createDirectories(paths.fs("results"));
            String resultFileName = jobId + ".result";
            String previewFileName = jobId + ".jpg";
            Path resultPath = paths.fs("apply-results", resultFileName);
            Path jobPath = paths.fs("apply-jobs", jobId + ".lua");
            traceLogger.info("lightroom.apply.job.writing", jobId, Map.of(
                    "settingCount", safeDevelopSettings.size(),
                    "localAdjustmentCount", safeLocalAdjustments.size(),
                    "jobPath", jobPath.toString(),
                    "resultPath", resultPath.toString(),
                    "previewFileName", previewFileName
            ));
            Map<String, Object> job = new java.util.LinkedHashMap<>();
            job.put("id", jobId);
            job.put("action", safeLocalAdjustments.isEmpty() ? "apply_develop_settings" : "apply_adjustments_with_local_guides");
            job.put("resultPath", paths.lightroom("apply-results", resultFileName));
            job.put("previewFileName", previewFileName);
            job.put("previewPath", paths.lightroom("results", previewFileName));
            job.put("developSettings", safeDevelopSettings);
            job.put("localAdjustments", safeLocalAdjustments);
            Files.writeString(jobPath, "return " + toLuaTable(job));
            String message = safeLocalAdjustments.isEmpty()
                    ? "已提交 Lightroom 调色任务，等待插件处理完成。"
                    : "已提交 Lightroom 调色与局部蒙版引导任务，等待插件处理完成。";
            Map<String, Object> result = Map.of(
                    "success", true,
                    "pending", true,
                    "jobId", jobId,
                    "message", message,
                    "previewFileName", previewFileName,
                    "localAdjustmentCount", safeLocalAdjustments.size()
            );
            traceLogger.info("lightroom.apply.submitted", jobId, result);
            return result;
        } catch (Exception exception) {
            traceLogger.error("lightroom.apply.failed", jobId, Map.of("error", exception.getMessage()));
            return Map.of("success", false, "message", "写入 Lightroom 调色任务失败：" + exception.getMessage());
        }
    }

    public Map<String, Object> applyStatus(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Map.of("success", false, "message", "缺少 Lightroom 调色任务 ID。");
        }
        Path resultPath = new BridgePaths(properties).fs("apply-results", jobId + ".result");
        if (!Files.exists(resultPath)) {
            traceLogger.info("lightroom.apply.status.pending", jobId, Map.of("resultPath", resultPath.toString()));
            return Map.of(
                    "success", true,
                    "pending", true,
                    "jobId", jobId,
                    "message", "Lightroom 正在处理调色任务。"
            );
        }
        Map<String, Object> result = parseResult(jobId, resultPath);
        traceLogger.info("lightroom.apply.status.finished", jobId, result);
        return result;
    }

    private Map<String, Object> parseResult(String jobId, Path resultPath) {
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (String line : Files.readAllLines(resultPath)) {
                int index = line.indexOf('=');
                if (index > 0) {
                    result.put(line.substring(0, index), line.substring(index + 1));
                }
            }
            String previewUrl = String.valueOf(result.getOrDefault("previewUrl", ""));
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("success", "true".equals(String.valueOf(result.get("success"))));
            response.put("pending", false);
            response.put("jobId", jobId);
            response.put("message", String.valueOf(result.getOrDefault("message", "")));
            response.put("previewUrl", previewUrl);
            response.put("afterPreviewUrl", previewUrl);
            if (result.containsKey("localGuideMessage")) {
                response.put("localGuideMessage", result.get("localGuideMessage"));
            }
            if (result.containsKey("localAdjustmentCount")) {
                response.put("localAdjustmentCount", result.get("localAdjustmentCount"));
            }
            if (result.containsKey("localMaskCreatedCount")) {
                response.put("localMaskCreatedCount", result.get("localMaskCreatedCount"));
            }
            if (result.containsKey("localMaskNeedsUserPlacement")) {
                response.put("localMaskNeedsUserPlacement", result.get("localMaskNeedsUserPlacement"));
            }
            if ("true".equals(String.valueOf(result.get("localMaskNeedsUserPlacement")))) {
                response.put("localMaskStatus", "needs_user_placement");
                response.put("localMaskStatusText", "局部蒙版已下发到 Lightroom，但需要你在蒙版面板中确认区域位置。");
            } else if (result.containsKey("localMaskCreatedCount")) {
                response.put("localMaskStatus", "created_or_not_required");
                response.put("localMaskStatusText", "Lightroom 已处理本轮局部蒙版任务。");
            }
            return response;
        } catch (Exception exception) {
            traceLogger.error("lightroom.apply.result.read_failed", jobId, Map.of(
                    "resultPath", resultPath.toString(),
                    "error", exception.getMessage()
            ));
            return Map.of("success", false, "pending", false, "jobId", jobId, "message", "读取 Lightroom 应用结果失败：" + exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String toLuaTable(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                builder.append("[\"").append(escapeLua(String.valueOf(entry.getKey()))).append("\"]=")
                        .append(toLuaTable(entry.getValue()))
                        .append(",");
            }
            builder.append("}");
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("{");
            int index = 1;
            for (Object item : iterable) {
                builder.append("[").append(index++).append("]=")
                        .append(toLuaTable(item))
                        .append(",");
            }
            builder.append("}");
            return builder.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escapeLua(String.valueOf(value == null ? "" : value)) + "\"";
    }

    private String escapeLua(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
