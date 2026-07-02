package com.tonepilot.service;

import com.tonepilot.domain.knowledge.DouyinImportRequest;
import com.tonepilot.infrastructure.knowledge.douyin.DouyinTranscriptService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DouyinTranscriptServiceTest {

    @Test
    void passesNormalizedDouyinUrlAndMetadataToExternalCommand() {
        DouyinTranscriptService service = new DouyinTranscriptService();
        ReflectionTestUtils.setField(service, "command", "env");
        ReflectionTestUtils.setField(service, "commandShell", false);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10L);

        String shareText = "6.48 12/31 uSy:/ :1pm y@G.VY 大师仿色第十三期：watchluke｜蓝调忧郁感 # 调色教程 https://v.douyin.com/d5-hcVmtAOU/ 复制此链接，打开Dou音搜索，直接观看视频！";

        String transcript = service.extractTranscript(new DouyinImportRequest(
                shareText,
                "大师仿色第十三期：watchluke｜蓝调忧郁感",
                "watchluke",
                13L,
                "蓝调忧郁感教程"
        ));

        assertThat(transcript).contains("TONEPILOT_DOUYIN_URL=https://v.douyin.com/d5-hcVmtAOU/");
        assertThat(transcript).contains("TONEPILOT_DOUYIN_TITLE=大师仿色第十三期：watchluke｜蓝调忧郁感");
        assertThat(transcript).contains("TONEPILOT_DOUYIN_AUTHOR=watchluke");
        assertThat(transcript).contains("TONEPILOT_DOUYIN_NOTES=蓝调忧郁感教程");
    }

    @Test
    void failsWhenNoTranscriptCommandAndNoManualTranscriptNotes() {
        DouyinTranscriptService service = new DouyinTranscriptService();
        ReflectionTestUtils.setField(service, "command", "");

        assertThatThrownBy(() -> service.extractTranscript(new DouyinImportRequest(
                "https://v.douyin.com/d5-hcVmtAOU/",
                "蓝调忧郁感教程",
                "watchluke",
                null,
                ""
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置抖音字幕提取命令");
    }
}
