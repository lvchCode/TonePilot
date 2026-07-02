package com.tonepilot.infrastructure.knowledge.douyin;

import com.tonepilot.domain.knowledge.DouyinImportRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DouyinTranscriptService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    @Value("${tonepilot.ingestion.douyin.command:}")
    private String command;

    @Value("${tonepilot.ingestion.douyin.command-shell:false}")
    private boolean commandShell;

    @Value("${tonepilot.ingestion.douyin.timeout-seconds:300}")
    private long timeoutSeconds;

    public String extractTranscript(DouyinImportRequest request) {
        DouyinInput input = DouyinInput.from(request);
        if (command == null || command.isBlank()) {
            return manualFallbackOrFail(input);
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
                throw new IllegalStateException("抖音字幕提取命令执行超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("抖音字幕提取命令失败：" + output);
            }
            if (output.isBlank()) {
                throw new IllegalStateException("抖音字幕提取命令没有返回字幕内容");
            }
            return output;
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("抖音字幕提取失败：" + exception.getMessage(), exception);
        }
    }

    private List<String> commandArgs(DouyinInput input) {
        String rendered = renderCommand(input);
        if (commandShell) {
            if (isWindows()) {
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

    private String renderCommand(DouyinInput input) {
        return command
                .replace("{url}", input.url())
                .replace("{shareText}", input.shareText())
                .replace("{title}", input.title())
                .replace("{author}", input.author())
                .replace("{notes}", input.notes());
    }

    private void applyEnvironment(Map<String, String> environment, DouyinInput input) {
        environment.put("TONEPILOT_DOUYIN_URL", input.url());
        environment.put("TONEPILOT_DOUYIN_SHARE_TEXT", input.shareText());
        environment.put("TONEPILOT_DOUYIN_TITLE", input.title());
        environment.put("TONEPILOT_DOUYIN_AUTHOR", input.author());
        environment.put("TONEPILOT_DOUYIN_NOTES", input.notes());
    }

    private String manualFallbackOrFail(DouyinInput input) {
        if (input.notes().isBlank()) {
            throw new IllegalStateException("未配置抖音字幕提取命令，请配置 tonepilot.ingestion.douyin.command，或在备注中粘贴视频字幕/调色步骤后再导入。");
        }
        return """
                抖音视频链接：%s
                视频标题：%s
                作者：%s

                管理员手工备注/字幕：
                %s
                """.formatted(input.url(), input.title(), input.author(), input.notes()).trim();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record DouyinInput(String url, String shareText, String title, String author, String notes) {
        private static DouyinInput from(DouyinImportRequest request) {
            String raw = clean(request.videoUrl());
            String url = extractUrl(raw);
            return new DouyinInput(
                    url,
                    raw,
                    cleanOrDefault(request.title(), inferTitle(raw, url)),
                    cleanOrDefault(request.author(), "未知作者"),
                    clean(request.notes())
            );
        }

        private static String extractUrl(String raw) {
            Matcher matcher = URL_PATTERN.matcher(raw);
            if (!matcher.find()) {
                return raw;
            }
            return matcher.group().replaceAll("[，,。！？!]+$", "");
        }

        private static String inferTitle(String raw, String url) {
            String candidate = raw.replace(url, "")
                    .replace("复制此链接，打开Dou音搜索，直接观看视频！", "")
                    .replace("复制此链接，打开抖音搜索，直接观看视频！", "")
                    .trim();
            int marker = Math.max(candidate.indexOf("大师"), candidate.indexOf("调色"));
            if (marker >= 0) {
                candidate = candidate.substring(marker).trim();
            }
            return candidate.isBlank() ? "抖音调色教程" : candidate;
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }

        private static String cleanOrDefault(String value, String fallback) {
            String cleaned = clean(value);
            return cleaned.isBlank() ? fallback : cleaned;
        }
    }
}
