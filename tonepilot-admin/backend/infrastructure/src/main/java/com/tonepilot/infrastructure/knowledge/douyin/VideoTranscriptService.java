package com.tonepilot.infrastructure.knowledge.douyin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class VideoTranscriptService {

    @Value("${tonepilot.ingestion.video.command:}")
    private String command;

    @Value("${tonepilot.ingestion.video.command-shell:false}")
    private boolean commandShell;

    @Value("${tonepilot.ingestion.video.timeout-seconds:900}")
    private long timeoutSeconds;

    public String transcribeVideo(Path videoPath, String fileName, String title, String author, String notes) {
        VideoInput input = new VideoInput(
                videoPath.toAbsolutePath().normalize().toString(),
                clean(fileName),
                cleanOrDefault(title, "上传抖音调色教程"),
                cleanOrDefault(author, "未知作者"),
                clean(notes)
        );
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("未配置上传视频转写命令，请配置 tonepilot.ingestion.video.command 后再导入。");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(commandArgs(input));
            builder.redirectErrorStream(true);
            applyEnvironment(builder.environment(), input);
            Process process = builder.start();
            boolean finished = process.waitFor(Duration.ofSeconds(timeoutSeconds).toMillis(), TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("上传视频转写命令执行超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("上传视频转写命令失败：" + output);
            }
            if (output.isBlank()) {
                throw new IllegalStateException("上传视频转写命令没有返回字幕内容");
            }
            return output;
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("上传视频转写失败：" + exception.getMessage(), exception);
        }
    }

    private List<String> commandArgs(VideoInput input) {
        String rendered = renderCommand(input);
        if (commandShell) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                return List.of("cmd", "/c", rendered);
            }
            return List.of("bash", "-lc", rendered);
        }
        List<String> args = new ArrayList<>();
        for (String part : rendered.split("\\s+")) {
            if (!part.isBlank()) {
                args.add(part);
            }
        }
        return args;
    }

    private String renderCommand(VideoInput input) {
        return command
                .replace("{videoPath}", input.videoPath())
                .replace("{fileName}", input.fileName())
                .replace("{title}", input.title())
                .replace("{author}", input.author())
                .replace("{notes}", input.notes());
    }

    private void applyEnvironment(Map<String, String> environment, VideoInput input) {
        environment.put("TONEPILOT_VIDEO_PATH", input.videoPath());
        environment.put("TONEPILOT_VIDEO_FILE_NAME", input.fileName());
        environment.put("TONEPILOT_VIDEO_TITLE", input.title());
        environment.put("TONEPILOT_VIDEO_AUTHOR", input.author());
        environment.put("TONEPILOT_VIDEO_NOTES", input.notes());
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String cleanOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private record VideoInput(String videoPath, String fileName, String title, String author, String notes) {
    }
}
