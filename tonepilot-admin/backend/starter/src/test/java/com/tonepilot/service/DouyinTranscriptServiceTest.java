package com.tonepilot.service;

import com.tonepilot.domain.knowledge.DouyinImportRequest;
import com.tonepilot.infrastructure.knowledge.douyin.DouyinTranscriptService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DouyinTranscriptServiceTest {

    @Test
    void convertsShareTextAndManualTranscriptNotesInsideJavaService() {
        DouyinTranscriptService service = new DouyinTranscriptService();
        String shareText = "6.48 12/31 uSy:/ :1pm y@G.VY 大师仿色第十三期：watchluke｜蓝调忧郁感 # 调色教程 https://v.douyin.com/d5-hcVmtAOU/ 复制此链接，打开Dou音搜索，直接观看视频！";

        String transcript = service.extractTranscript(new DouyinImportRequest(
                shareText,
                "大师仿色第十三期：watchluke｜蓝调忧郁感",
                "watchluke",
                13L,
                "蓝调忧郁感教程：压低高光，降低整体色温，增强蓝色饱和度。"
        ));

        assertThat(transcript).contains("抖音视频链接：https://v.douyin.com/d5-hcVmtAOU/");
        assertThat(transcript).contains("视频标题：大师仿色第十三期：watchluke｜蓝调忧郁感");
        assertThat(transcript).contains("作者：watchluke");
        assertThat(transcript).contains("管理员手工备注/字幕：");
        assertThat(transcript).contains("压低高光");
    }

    @Test
    void failsWhenDouyinLinkHasNoTranscriptNotes() {
        DouyinTranscriptService service = new DouyinTranscriptService();

        assertThatThrownBy(() -> service.extractTranscript(new DouyinImportRequest(
                "https://v.douyin.com/d5-hcVmtAOU/",
                "蓝调忧郁感教程",
                "watchluke",
                null,
                ""
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("备注中粘贴字幕");
    }
}
