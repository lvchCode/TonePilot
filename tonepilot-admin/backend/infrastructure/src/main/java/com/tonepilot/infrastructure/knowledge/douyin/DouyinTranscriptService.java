package com.tonepilot.infrastructure.knowledge.douyin;

import com.tonepilot.domain.knowledge.DouyinImportRequest;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DouyinTranscriptService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    public String extractTranscript(DouyinImportRequest request) {
        DouyinInput input = DouyinInput.from(request);
        if (input.notes().isBlank()) {
            throw new IllegalStateException("抖音链接导入需要在备注中粘贴字幕、调色步骤或教程摘要；如果只有视频文件，请使用上传抖音视频文件入口自动转写。");
        }
        return """
                抖音视频链接：%s
                视频标题：%s
                作者：%s

                管理员手工备注/字幕：
                %s
                """.formatted(input.url(), input.title(), input.author(), input.notes()).trim();
    }

    private record DouyinInput(String url, String title, String author, String notes) {
        private static DouyinInput from(DouyinImportRequest request) {
            String raw = clean(request.videoUrl());
            String url = extractUrl(raw);
            return new DouyinInput(
                    url,
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
