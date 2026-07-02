package com.tonepilot.infrastructure.knowledge.douyin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class VideoTranscriptService {

    @Value("${tonepilot.ingestion.video.skill-dir:}")
    private String skillDir;

    @Value("${tonepilot.ingestion.video.ffmpeg-bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${tonepilot.ingestion.video.python-bin:python3}")
    private String pythonBin;

    @Value("${tonepilot.ingestion.video.timeout-seconds:900}")
    private long timeoutSeconds;

    @Value("${tonepilot.ingestion.video.transcript-override:}")
    private String transcriptOverride;

    public String transcribeVideo(Path videoPath, String fileName, String title, String author, String notes) {
        VideoInput input = new VideoInput(
                videoPath.toAbsolutePath().normalize(),
                clean(fileName),
                cleanOrDefault(title, "上传抖音调色教程"),
                cleanOrDefault(author, "未知作者"),
                clean(notes)
        );
        if (!Files.exists(input.videoPath())) {
            throw new IllegalStateException("视频文件不存在：" + input.videoPath());
        }
        if (transcriptOverride != null && !transcriptOverride.isBlank()) {
            return buildTranscript(input, transcriptOverride.trim());
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("tonepilot-video-");
            Path audioPath = workDir.resolve("audio.wav");
            runCommand(List.of(
                    ffmpegBin, "-y", "-i", input.videoPath().toString(),
                    "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", audioPath.toString()
            ), "ffmpeg 提取视频音频");

            Path transcriptScript = findTranscriptScript();
            ProcessResult transcript = runCommand(List.of(
                    pythonBin, transcriptScript.toString(), audioPath.toString()
            ), "faster-whisper 转写视频音频");
            String transcriptText = transcript.output().trim();
            if (transcriptText.isBlank()) {
                throw new IllegalStateException("上传视频转写没有返回字幕内容");
            }
            return buildTranscript(input, transcriptText);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("上传视频转写失败：" + exception.getMessage(), exception);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private Path findTranscriptScript() {
        for (Path candidate : skillDirCandidates()) {
            Path script = candidate.resolve("scripts").resolve("transcribe_faster_whisper.py");
            if (Files.exists(script)) {
                return script.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("未找到 video-to-subtitle-summary skill，请配置 tonepilot.ingestion.video.skill-dir 或 VIDEO_TO_SUBTITLE_SUMMARY_SKILL_DIR。");
    }

    private List<Path> skillDirCandidates() {
        Path home = Path.of(System.getProperty("user.home", "."));
        if (skillDir != null && !skillDir.isBlank()) {
            return List.of(
                    Path.of(skillDir).toAbsolutePath().normalize(),
                    home.resolve(".codex/skills/video-to-subtitle-summary").toAbsolutePath().normalize(),
                    home.resolve(".codex/skills/video-to-subtitle-summary-skill").toAbsolutePath().normalize()
            );
        }
        return List.of(
                home.resolve(".codex/skills/video-to-subtitle-summary").toAbsolutePath().normalize(),
                home.resolve(".codex/skills/video-to-subtitle-summary-skill").toAbsolutePath().normalize()
        );
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
            throw new IllegalStateException(action + "失败：" + exception.getMessage(), exception);
        }
    }

    private String readOutput(Process process) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            process.getInputStream().transferTo(outputStream);
            return outputStream.toString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return exception.getMessage();
        }
    }

    private String buildTranscript(VideoInput input, String transcriptText) {
        StringBuilder builder = new StringBuilder()
                .append("视频标题：").append(input.title()).append('\n')
                .append("作者：").append(input.author()).append('\n')
                .append("文件名：").append(input.fileName()).append('\n');
        if (!input.notes().isBlank()) {
            builder.append("管理员备注：").append(input.notes()).append('\n');
        }
        builder.append('\n')
                .append("字幕转写：").append('\n')
                .append(transcriptText.trim());
        return builder.toString();
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响导入结果。
                }
            });
        } catch (Exception ignored) {
            // 临时目录清理失败不影响导入结果。
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String cleanOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private record VideoInput(Path videoPath, String fileName, String title, String author, String notes) {
    }

    private record ProcessResult(String output) {
    }
}
