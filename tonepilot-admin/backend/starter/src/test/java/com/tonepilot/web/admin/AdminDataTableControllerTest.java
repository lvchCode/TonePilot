package com.tonepilot.web.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.starter.TonePilotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TonePilotApplication.class, properties = {
        "tonepilot.persistence.enabled=true",
        "tonepilot.rate-limit.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:tonepilot_admin_data_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class AdminDataTableControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void tableTreeContainsBusinessDataAndEditableFlags() throws Exception {
        JsonNode payload = getJson("/api/admin/data/tables/tree");

        JsonNode groups = payload.path("data");
        assertThat(groups).anyMatch(group -> group.path("label").asText().equals("业务数据"));
        assertThat(groups).anyMatch(group -> group.path("label").asText().equals("知识库数据"));

        JsonNode knowledgeSource = findTable(groups, "knowledge_source");
        assertThat(knowledgeSource.path("editable").asBoolean()).isTrue();
        assertThat(knowledgeSource.path("columns")).anyMatch(column -> column.path("name").asText().equals("title"));

        JsonNode runtimeEvent = findTable(groups, "runtime_event");
        assertThat(runtimeEvent.path("editable").asBoolean()).isFalse();
    }

    @Test
    void editableTableCanCreateAndQueryRows() throws Exception {
        String createResponse = mockMvc.perform(post("/api/admin/data/tables/knowledge_source/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 88001,
                                  "source_type": "manual_note",
                                  "title": "后台 CRUD 测试素材",
                                  "author": "TonePilot",
                                  "status": "active"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(createResponse).path("success").asBoolean()).isTrue();

        JsonNode rows = getJson("/api/admin/data/tables/knowledge_source/rows?keyword=CRUD&page=1&size=20")
                .path("data")
                .path("rows");
        assertThat(rows).anyMatch(row -> row.path("title").asText().equals("后台 CRUD 测试素材"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_source WHERE id = 88001", Integer.class)).isEqualTo(1);
    }

    @Test
    void readonlyTableRejectsCreate() throws Exception {
        String response = mockMvc.perform(post("/api/admin/data/tables/runtime_event/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode payload = objectMapper.readTree(response);
        assertThat(payload.path("success").asBoolean()).isFalse();
        assertThat(payload.path("message").asText()).contains("只读");
    }

    private JsonNode getJson(String url) throws Exception {
        String response = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private JsonNode findTable(JsonNode groups, String tableName) {
        for (JsonNode group : groups) {
            for (JsonNode child : group.path("children")) {
                if (child.path("tableName").asText().equals(tableName)) {
                    return child;
                }
            }
        }
        throw new AssertionError("未找到表：" + tableName);
    }
}

