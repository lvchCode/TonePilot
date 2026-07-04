package com.tonepilot.web.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.starter.TonePilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TonePilotApplication.class, properties = {
        "tonepilot.persistence.enabled=false",
        "tonepilot.rate-limit.enabled=false",
        "tonepilot.ingestion.video.transcript-override=[00:00:00.000-00:00:04.000] 先压高光。\\n[00:00:04.000-00:00:08.000] 再提高阴影，蓝色饱和度降低。",
        "tonepilot.ingestion.video.visual-analysis-override=关键帧视觉分析：画面是蓝调夜景，原片偏灰，教程展示了天空压暗、蓝色降低饱和度、曲线增加对比。",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class AdminKnowledgeMaterialControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsSourceImportsMaterialAndExtractsKnowledge() throws Exception {
        JsonNode sourceResponse = postJson("/api/admin/knowledge-sources", """
                {
                  "sourceType": "master_edit_record",
                  "title": "大师夜景调色记录",
                  "author": "摄影师 A",
                  "originalUrl": "https://example.com/edit-record",
                  "styleId": 2,
                  "notes": "记录一次城市夜景成片的全局参数"
                }
                """);
        long sourceId = sourceResponse.path("data").path("id").asLong();

        JsonNode materialResponse = postJson("/api/admin/knowledge-sources/" + sourceId + "/materials", """
                {
                  "materialType": "param_delta",
                  "title": "参数变化说明",
                  "content": "高光降低，阴影提升，蓝色饱和度略降，暗角增强。",
                  "language": "zh-CN"
                }
                """);
        long materialId = materialResponse.path("data").path("id").asLong();

        JsonNode jobResponse = postJson(
                "/api/admin/knowledge-sources/" + sourceId + "/materials/" + materialId + "/extract",
                "{}"
        );

        assertThat(jobResponse.path("success").asBoolean()).isTrue();
        assertThat(jobResponse.path("data").path("status").asText()).isEqualTo("succeeded");
        assertThat(jobResponse.path("data").path("generatedKnowledgeId").asLong()).isPositive();
    }


    @Test
    void douyinLinkImportWithoutTranscriptReturnsReadableBadRequest() throws Exception {
        String response = mockMvc.perform(post("/api/admin/knowledge-sources/douyin-imports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoUrl": "https://v.douyin.com/d5-hcVmtAOU/",
                                  "title": "watchluke 蓝调忧郁感"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode payload = objectMapper.readTree(response);
        assertThat(payload.path("success").asBoolean()).isFalse();
        assertThat(payload.path("message").asText()).contains("上传抖音视频文件入口");
    }

    @Test
    void uploadsDouyinVideoAndExtractsKnowledge() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "watchluke-blue.mp4",
                "video/mp4",
                "fake-video-content".getBytes()
        );

        String response = mockMvc.perform(multipart("/api/admin/knowledge-sources/douyin-video-uploads")
                        .file(file)
                        .param("title", "watchluke 蓝调忧郁感")
                        .param("author", "watchluke")
                        .param("styleId", "2")
                        .param("notes", "上传抖音视频后自动转字幕入库"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode payload = objectMapper.readTree(response);
        assertThat(payload.path("success").asBoolean()).isTrue();
        assertThat(payload.path("data").path("status").asText()).isEqualTo("succeeded");
        assertThat(payload.path("data").path("generatedKnowledgeId").asLong()).isPositive();

        long sourceId = payload.path("data").path("sourceId").asLong();
        String materialsResponse = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/knowledge-sources/" + sourceId + "/materials"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode materialsPayload = objectMapper.readTree(materialsResponse);
        String materialContent = materialsPayload.path("data").get(0).path("content").asText();
        assertThat(materialContent).contains("时间戳字幕：");
        assertThat(materialContent).contains("[00:00:00.000-00:00:04.000] 先压高光。");
        assertThat(materialContent).contains("关键帧视觉分析");
    }

    private JsonNode postJson(String url, String body) throws Exception {
        String response = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }
}
