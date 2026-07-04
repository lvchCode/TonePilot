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

    @Value("${tonepilot.ingestion.video.asr-provider:whisper-cpp}")
    private String asrProvider;

    @Value("${tonepilot.ingestion.video.ffmpeg-bin:ffmpeg}")
    private String ffmpegBin;

    @Value("${tonepilot.ingestion.video.whisper-cpp-bin:whisper-cli}")
    private String whisperCppBin;

    @Value("${tonepilot.ingestion.video.whisper-model:}")
    private String whisperModel;

    @Value("${tonepilot.ingestion.video.language:zh}")
    private String language;

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
            extractAudio(input.videoPath(), audioPath);
            String transcriptText = transcribeAudio(audioPath, workDir);
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

    private void extractAudio(Path videoPath, Path audioPath) {
        runCommand(List.of(
                ffmpegBin, "-y", "-i", videoPath.toString(),
                "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", audioPath.toString()
        ), "ffmpeg 提取视频音频");
    }

    private String transcribeAudio(Path audioPath, Path workDir) {
        String provider = cleanOrDefault(asrProvider, "whisper-cpp");
        if (!"whisper-cpp".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("不支持的视频转写提供者：" + provider + "，当前支持 whisper-cpp。");
        }
        return transcribeWithWhisperCpp(audioPath, workDir);
    }

    private String transcribeWithWhisperCpp(Path audioPath, Path workDir) {
        if (whisperModel == null || whisperModel.isBlank()) {
            throw new IllegalStateException("未配置 whisper.cpp 模型文件，请配置 tonepilot.ingestion.video.whisper-model。");
        }
        Path outputPrefix = workDir.resolve("subtitle");
        ProcessResult result = runCommand(List.of(
                whisperCppBin,
                "-m", Path.of(whisperModel).toAbsolutePath().normalize().toString(),
                "-f", audioPath.toString(),
                "-l", cleanOrDefault(language, "zh"),
                "-osrt",
                "-otxt",
                "-of", outputPrefix.toString()
        ), "whisper.cpp 本地转写视频音频");

        Path srtPath = Path.of(outputPrefix + ".srt");
        if (Files.exists(srtPath)) {
            try {
                return normalizeSrt(Files.readString(srtPath, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IllegalStateException("读取 whisper.cpp 时间戳字幕失败：" + exception.getMessage(), exception);
            }
        }
        Path textPath = Path.of(outputPrefix + ".txt");
        if (Files.exists(textPath)) {
            try {
                return normalizePlainTranscript(Files.readString(textPath, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IllegalStateException("读取 whisper.cpp 字幕文本失败：" + exception.getMessage(), exception);
            }
        }
        return normalizePlainTranscript(normalizeWhisperStdout(result.output()));
    }

    private String normalizeWhisperStdout(String output) {
        StringBuilder builder = new StringBuilder();
        for (String line : output.split("\\R")) {
            String cleaned = line.trim();
            if (cleaned.isBlank()
                    || cleaned.startsWith("whisper_")
                    || cleaned.startsWith("main:")
                    || cleaned.startsWith("system_info:")
                    || cleaned.startsWith("[")
                    || cleaned.startsWith("{")) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(cleaned);
        }
        return builder.toString().trim();
    }

    private String normalizeSrt(String srtText) {
        StringBuilder builder = new StringBuilder();
        String currentRange = "";
        StringBuilder currentText = new StringBuilder();
        for (String rawLine : srtText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                appendSubtitleLine(builder, currentRange, currentText.toString());
                currentRange = "";
                currentText.setLength(0);
                continue;
            }
            if (line.matches("\\d+")) {
                continue;
            }
            if (line.contains("-->")) {
                appendSubtitleLine(builder, currentRange, currentText.toString());
                currentText.setLength(0);
                String[] parts = line.split("-->");
                currentRange = formatTimestamp(parts[0]) + "-" + formatTimestamp(parts.length > 1 ? parts[1] : "");
                continue;
            }
            if (!currentText.isEmpty()) {
                currentText.append(' ');
            }
            currentText.append(line);
        }
        appendSubtitleLine(builder, currentRange, currentText.toString());
        return builder.toString().trim();
    }

    private void appendSubtitleLine(StringBuilder builder, String range, String text) {
        String cleaned = clean(text);
        if (cleaned.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append('[').append(clean(range).isBlank() ? "无时间戳" : clean(range)).append("] ").append(cleaned);
    }

    private String normalizePlainTranscript(String transcriptText) {
        String cleaned = clean(transcriptText);
        if (cleaned.isBlank()) {
            return "";
        }
        if (cleaned.matches("(?s).*\\[[^\\]]*\\d{2}:\\d{2}[^\\]]*].*")) {
            return cleaned;
        }
        return "[无时间戳] " + cleaned.replaceAll("\\R+", " ").trim();
    }

    private String formatTimestamp(String value) {
        String cleaned = clean(value).replace(',', '.');
        int spaceIndex = cleaned.indexOf(' ');
        return spaceIndex >= 0 ? cleaned.substring(0, spaceIndex) : cleaned;
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
                .append("时间戳字幕：").append('\n')
                .append(normalizePlainTranscript(transcriptText));
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
