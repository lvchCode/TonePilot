package com.tonepilot.infrastructure.knowledge.douyin;

import com.tonepilot.infrastructure.ai.AiProperties;
import com.tonepilot.infrastructure.ai.OpenAiCompatibleModelClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class VideoFrameAnalysisService {

    @Value("${tonepilot.ingestion.video.frame-analysis-enabled:true}")
    private boolean frameAnalysisEnabled;

    @Value("${tonepilot.ingestion.video.ffmpeg-bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${tonepilot.ingestion.video.frame-interval-seconds:5}")
    private int frameIntervalSeconds;

    @Value("${tonepilot.ingestion.video.max-keyframes:6}")
    private int maxKeyframes;

    @Value("${tonepilot.ingestion.video.timeout-seconds:900}")
    private long timeoutSeconds;

    @Value("${tonepilot.ingestion.video.visual-analysis-override:}")
    private String visualAnalysisOverride;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private OpenAiCompatibleModelClient modelClient;

    public String analyzeVideo(Path videoPath, String fileName, String title, String notes) {
        if (!frameAnalysisEnabled) {
            return "";
        }
        if (visualAnalysisOverride != null && !visualAnalysisOverride.isBlank()) {
            return buildAnalysisSection(List.of(), visualAnalysisOverride.trim());
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("tonepilot-video-frames-");
            List<Path> frames = extractKeyframes(videoPath.toAbsolutePath().normalize(), workDir);
            if (frames.isEmpty()) {
                return "关键帧视觉分析：未能从视频中抽取关键帧，请检查视频编码或时长。";
            }
            if (!aiProperties.modelEnabled()) {
                return buildAnalysisSection(frames, "当前未启用视觉模型，仅完成关键帧抽取；请配置 OpenAI/Qwen 视觉模型后获得画面内容、前后对比和参数面板分析。");
            }
            List<String> frameReports = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                frameReports.add(analyzeFrame(frames.get(i), i + 1, fileName, title, notes));
            }
            return buildAnalysisSection(frames, String.join("\n\n", frameReports));
        } catch (Exception exception) {
            return "关键帧视觉分析：视频画面分析失败，原因：" + exception.getMessage();
        } finally {
            deleteQuietly(workDir);
        }
    }

    private List<Path> extractKeyframes(Path videoPath, Path workDir) {
        int interval = Math.max(1, frameIntervalSeconds);
        int limit = Math.max(1, maxKeyframes);
        Path outputPattern = workDir.resolve("frame-%03d.jpg");
        runCommand(List.of(
                ffmpegBin,
                "-y",
                "-i", videoPath.toString(),
                "-vf", "fps=1/" + interval + ",scale=min(1280\\,iw):-2",
                "-frames:v", String.valueOf(limit),
                outputPattern.toString()
        ), "ffmpeg 抽取视频关键帧");
        try {
            List<Path> frames = new ArrayList<>();
            try (var stream = Files.list(workDir)) {
                stream
                        .filter(path -> path.getFileName().toString().startsWith("frame-"))
                        .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                        .sorted()
                        .limit(limit)
                        .forEach(frames::add);
            }
            return frames;
        } catch (Exception exception) {
            throw new IllegalStateException("读取关键帧失败：" + exception.getMessage(), exception);
        }
    }

    private String analyzeFrame(Path framePath, int index, String fileName, String title, String notes) {
        String json = modelClient.completeVisionJson(
                "你是 TonePilot 视频调色教程画面分析 Agent，只输出严格 JSON。",
                """
                        请分析这个调色教程关键帧，输出 JSON 字段：
                        frameType: 画面类型，例如 原图、成片、前后对比、Lightroom 参数面板、曲线面板、HSL 面板、蒙版区域、普通讲解画面
                        visualState: 画面主体、明暗、色彩倾向、质感和明显问题
                        editingClues: 从画面能看出的调色动作、参数变化或局部区域
                        knowledgeValue: 这帧对沉淀调色知识有什么价值

                        视频文件：%s
                        视频标题：%s
                        管理员备注：%s
                        关键帧序号：%d
                        """.formatted(clean(fileName), clean(title), clean(notes), index),
                toDataUrl(framePath)
        );
        FrameVisionOutput output = modelClient.readJson(json, FrameVisionOutput.class);
        return """
                关键帧 %d
                画面类型：%s
                画面观察：%s
                调色线索：%s
                知识价值：%s
                """.formatted(
                index,
                cleanOrDefault(output.frameType(), "未识别"),
                cleanOrDefault(output.visualState(), "模型未返回画面观察"),
                cleanOrDefault(output.editingClues(), "模型未返回调色线索"),
                cleanOrDefault(output.knowledgeValue(), "模型未返回知识价值")
        ).trim();
    }

    private String buildAnalysisSection(List<Path> frames, String analysis) {
        String frameSummary = frames.isEmpty()
                ? ""
                : "\n抽取关键帧：" + frames.size() + " 张";
        return "关键帧视觉分析：" + frameSummary + "\n" + analysis;
    }

    private String toDataUrl(Path path) {
        try {
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            return "data:image/jpeg;base64," + encoded;
        } catch (Exception exception) {
            throw new IllegalStateException("读取关键帧图片失败：" + exception.getMessage(), exception);
        }
    }

    private ProcessResult runCommand(List<String> command, String action) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
            boolean finished = process.waitFor(Duration.ofSeconds(timeoutSeconds).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(action + "超时");
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(action + "失败：" + output);
            }
            return new ProcessResult(output);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(action + "异常：" + exception.getMessage(), exception);
        }
    }

    private String readOutput(Process process) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left))
                    .forEach(item -> {
                        try {
                            Files.deleteIfExists(item);
                        } catch (Exception ignored) {
                            // 临时关键帧清理失败不影响导入结果。
                        }
                    });
        } catch (Exception ignored) {
            // 临时关键帧清理失败不影响导入结果。
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String cleanOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private record ProcessResult(String output) {
    }

    private record FrameVisionOutput(
            String frameType,
            String visualState,
            String editingClues,
            String knowledgeValue
    ) {
    }
}
