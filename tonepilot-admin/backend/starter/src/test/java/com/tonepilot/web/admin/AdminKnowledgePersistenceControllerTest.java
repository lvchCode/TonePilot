package com.tonepilot.web.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.starter.TonePilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TonePilotApplication.class, properties = {
        "tonepilot.persistence.enabled=true",
        "tonepilot.rate-limit.enabled=false",
        "tonepilot.ingestion.video.transcript-override=上传视频字幕：降低高光，提升阴影，增加蓝色饱和度。",
        "spring.datasource.url=jdbc:h2:mem:tonepilot_knowledge_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class AdminKnowledgePersistenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uploadedVideoImportWritesStructuredKnowledgeTables() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "blue-tone.mp4",
                "video/mp4",
                "fake-video-content".getBytes()
        );

        String response = mockMvc.perform(multipart("/api/admin/knowledge-sources/douyin-video-uploads")
                        .file(file)
                        .param("title", "蓝调忧郁感教程")
                        .param("author", "watchluke")
                        .param("styleId", "2")
                        .param("notes", "数据库持久化测试"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode payload = objectMapper.readTree(response);
        assertThat(payload.path("data").path("generatedKnowledgeId").asLong()).isPositive();
        assertThat(count("knowledge_source")).isEqualTo(1);
        assertThat(count("knowledge_material")).isEqualTo(1);
        assertThat(count("knowledge_extraction_job")).isEqualTo(1);
        assertThat(count("style_knowledge")).isEqualTo(1);
        assertThat(count("domain_snapshot")).isGreaterThanOrEqualTo(4);
    }

    private Integer count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
