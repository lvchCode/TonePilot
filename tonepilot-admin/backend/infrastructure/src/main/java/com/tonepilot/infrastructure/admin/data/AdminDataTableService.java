package com.tonepilot.infrastructure.admin.data;

import com.tonepilot.repository.admin.data.AdminDataUpdateCommand;
import com.tonepilot.repository.admin.data.AdminDataTableRepository;
import com.tonepilot.repository.admin.data.AdminDataListQuery;
import com.tonepilot.repository.admin.data.AdminDataInsertCommand;
import com.tonepilot.repository.admin.data.AdminDataFindQuery;
import com.tonepilot.repository.admin.data.AdminDataDeleteCommand;
import com.tonepilot.repository.admin.data.AdminDataCountQuery;
import com.tonepilot.repository.admin.data.AdminDataColumnValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AdminDataTableService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final ObjectProvider<AdminDataTableRepository> repositoryProvider;
    private final List<TableGroup> groups;
    private final Map<String, TableDefinition> tableDefinitions;

    @Autowired
    public AdminDataTableService(ObjectProvider<AdminDataTableRepository> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
        this.groups = buildGroups();
        this.tableDefinitions = groups.stream()
                .flatMap(group -> group.children().stream())
                .collect(Collectors.toMap(
                        TableDefinition::tableName,
                        definition -> definition,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    public List<TableGroup> tableTree() {
        return groups;
    }

    public TableRows listRows(String tableName, String keyword, int page, int size) {
        TableDefinition table = table(tableName);
        int currentPage = Math.max(page, DEFAULT_PAGE);
        int currentSize = Math.max(1, Math.min(size <= 0 ? DEFAULT_SIZE : size, MAX_SIZE));
        String keywordPattern = keywordPattern(keyword);
        long total = repository().countRows(new AdminDataCountQuery(
                table.tableName(),
                table.searchableColumns(),
                keywordPattern
        ));
        List<Map<String, Object>> rows = repository().listRows(new AdminDataListQuery(
                table.tableName(),
                columnList(table),
                table.orderColumn(),
                table.searchableColumns(),
                keywordPattern,
                currentSize,
                (currentPage - 1) * currentSize
        )).stream().map(row -> normalizeRow(table, row)).toList();
        return new TableRows(rows, total, currentPage, currentSize);
    }

    public Map<String, Object> createRow(String tableName, Map<String, Object> request) {
        TableDefinition table = editableTable(tableName);
        Map<String, Object> values = sanitizeForWrite(table, request, true);
        ensureRequiredColumns(table, values);
        List<String> columns = new ArrayList<>(values.keySet());
        List<Object> writeValues = columns.stream()
                .map(column -> convertValue(table.column(column), values.get(column)))
                .collect(Collectors.toCollection(ArrayList::new));
        repository().insertRow(new AdminDataInsertCommand(table.tableName(), columns, writeValues));
        return getByPrimaryKey(table, values.get(table.primaryKey()));
    }

    public Map<String, Object> updateRow(String tableName, String rowKey, Map<String, Object> request) {
        TableDefinition table = editableTable(tableName);
        Map<String, Object> values = sanitizeForWrite(table, request, false);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("没有可更新的字段");
        }
        List<AdminDataColumnValue> writeValues = values.entrySet().stream()
                .map(entry -> new AdminDataColumnValue(
                        entry.getKey(),
                        convertValue(table.column(entry.getKey()), entry.getValue())
                ))
                .collect(Collectors.toCollection(ArrayList::new));
        int updated = repository().updateRow(new AdminDataUpdateCommand(
                table.tableName(),
                writeValues,
                table.primaryKey(),
                convertPrimaryKey(table, rowKey)
        ));
        if (updated == 0) {
            throw new IllegalArgumentException("未找到要更新的数据：" + rowKey);
        }
        return getByPrimaryKey(table, convertPrimaryKey(table, rowKey));
    }

    public void deleteRow(String tableName, String rowKey) {
        TableDefinition table = editableTable(tableName);
        int updated = repository().deleteRow(new AdminDataDeleteCommand(
                table.tableName(),
                table.primaryKey(),
                convertPrimaryKey(table, rowKey)
        ));
        if (updated == 0) {
            throw new IllegalArgumentException("未找到要删除的数据：" + rowKey);
        }
    }

    public TableDefinition describe(String tableName) {
        return table(tableName);
    }

    private AdminDataTableRepository repository() {
        AdminDataTableRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("当前未启用数据库连接，无法访问数据管理表");
        }
        return repository;
    }

    private TableDefinition table(String tableName) {
        TableDefinition definition = tableDefinitions.get(tableName);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的数据表：" + tableName);
        }
        return definition;
    }

    private TableDefinition editableTable(String tableName) {
        TableDefinition definition = table(tableName);
        if (!definition.editable()) {
            throw new IllegalArgumentException("数据表 " + definition.label() + " 为只读，不能直接增删改");
        }
        if (definition.primaryKey() == null || definition.primaryKey().isBlank()) {
            throw new IllegalArgumentException("数据表 " + definition.label() + " 没有单字段主键，不能直接增删改");
        }
        return definition;
    }

    private Map<String, Object> sanitizeForWrite(TableDefinition table, Map<String, Object> request, boolean create) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (request != null) {
            request.forEach((key, value) -> {
                ColumnDefinition column = table.column(key);
                if (column != null && column.editable()) {
                    values.put(key, value);
                }
            });
        }
        Instant now = Instant.now();
        if (create && table.hasColumn(table.primaryKey()) && !values.containsKey(table.primaryKey())) {
            ColumnDefinition primaryKey = table.column(table.primaryKey());
            if ("number".equals(primaryKey.type())) {
                values.put(table.primaryKey(), System.currentTimeMillis());
            }
        }
        if (create && table.hasColumn("created_at") && !values.containsKey("created_at")) {
            values.put("created_at", now);
        }
        if (table.hasColumn("updated_at") && !values.containsKey("updated_at")) {
            values.put("updated_at", now);
        }
        return values;
    }

    private void ensureRequiredColumns(TableDefinition table, Map<String, Object> values) {
        for (ColumnDefinition column : table.columns()) {
            if (!column.required()) {
                continue;
            }
            Object value = values.get(column.name());
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("字段 " + column.label() + " 不能为空");
            }
        }
    }

    private Map<String, Object> getByPrimaryKey(TableDefinition table, Object primaryKey) {
        Map<String, Object> row = repository().findByPrimaryKey(new AdminDataFindQuery(
                table.tableName(),
                columnList(table),
                table.primaryKey(),
                convertValue(table.column(table.primaryKey()), primaryKey)
        ));
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("未找到数据：" + primaryKey);
        }
        return normalizeRow(table, row);
    }

    private String keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase() + "%";
    }

    private Map<String, Object> normalizeRow(TableDefinition table, Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (ColumnDefinition column : table.columns()) {
            Object value = findValue(row, column.name());
            if (value instanceof Timestamp timestamp) {
                normalized.put(column.name(), timestamp.toInstant().toString());
            } else if (value instanceof java.sql.Date date) {
                normalized.put(column.name(), date.toString());
            } else {
                normalized.put(column.name(), value);
            }
        }
        if (table.primaryKey() != null && !table.primaryKey().isBlank()) {
            normalized.put("_rowKey", Objects.toString(normalized.get(table.primaryKey()), ""));
        }
        return normalized;
    }

    private Object findValue(Map<String, Object> row, String columnName) {
        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }
        return row.get(columnName.toUpperCase());
    }

    private Object convertPrimaryKey(TableDefinition table, String rowKey) {
        return convertValue(table.column(table.primaryKey()), rowKey);
    }

    private Object convertValue(ColumnDefinition column, Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        return switch (column.type()) {
            case "number" -> {
                if (value instanceof Number number) {
                    yield number.longValue();
                }
                String text = value.toString();
                yield text.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
            }
            case "boolean" -> {
                if (value instanceof Boolean bool) {
                    yield bool;
                }
                yield Boolean.parseBoolean(value.toString());
            }
            case "timestamp" -> toTimestamp(value);
            default -> value.toString();
        };
    }

    private Timestamp toTimestamp(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        String text = value.toString();
        try {
            return Timestamp.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            LocalDateTime dateTime = LocalDateTime.parse(text);
            return Timestamp.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
        }
    }

    private String columnList(TableDefinition table) {
        return table.columns().stream().map(ColumnDefinition::name).collect(Collectors.joining(", "));
    }

    private List<TableGroup> buildGroups() {
        return List.of(
                new TableGroup("business", "业务数据", "风格、样片和领域对象快照", List.of(
                        readonly("domain_snapshot", "业务对象快照", "业务对象的 JSON 快照，建议只读查看",
                                "updated_at",
                                List.of(
                                        col("domain_type", "领域类型", "text", false, true, true),
                                        col("domain_id", "领域 ID", "text", false, true, true),
                                        col("payload_json", "快照内容", "json", false, true, false),
                                        col("created_at", "创建时间", "timestamp", false, false, false),
                                        col("updated_at", "更新时间", "timestamp", false, false, false)
                                ),
                                List.of("domain_type", "domain_id", "payload_json"))
                )),
                new TableGroup("knowledge", "知识库数据", "素材来源、抽取任务、风格知识和检索分片", List.of(
                        editable("knowledge_source", "知识来源", "教程、样片、手工笔记等知识来源",
                                "id", "updated_at",
                                List.of(
                                        col("id", "ID", "number", true, true, false),
                                        col("source_type", "来源类型", "text", true, true, false),
                                        col("title", "标题", "text", true, true, false),
                                        col("author", "作者", "text", true, false, false),
                                        col("original_url", "原始链接", "text", true, false, false),
                                        col("style_id", "关联风格 ID", "number", true, false, false),
                                        col("notes", "备注", "longText", true, false, false),
                                        col("status", "状态", "text", true, false, false),
                                        col("created_at", "创建时间", "timestamp", true, false, false),
                                        col("updated_at", "更新时间", "timestamp", true, false, false)
                                ),
                                List.of("title", "author", "source_type", "notes")),
                        editable("knowledge_material", "知识素材", "已沉淀的字幕、摘要、参数记录等原始素材",
                                "id", "created_at",
                                List.of(
                                        col("id", "ID", "number", true, true, false),
                                        col("source_id", "来源 ID", "number", true, true, false),
                                        col("material_type", "素材类型", "text", true, true, false),
                                        col("title", "标题", "text", true, true, false),
                                        col("content", "内容", "longText", true, true, false),
                                        col("language", "语言", "text", true, false, false),
                                        col("created_at", "创建时间", "timestamp", true, false, false)
                                ),
                                List.of("title", "material_type", "content")),
                        readonly("knowledge_extraction_job", "知识抽取任务", "从素材生成风格知识的执行记录",
                                "updated_at",
                                List.of(
                                        col("id", "ID", "number", false, true, false),
                                        col("source_id", "来源 ID", "number", false, true, false),
                                        col("material_id", "素材 ID", "number", false, true, false),
                                        col("status", "状态", "text", false, false, false),
                                        col("generated_knowledge_id", "生成知识 ID", "number", false, false, false),
                                        col("message", "消息", "longText", false, false, false),
                                        col("created_at", "创建时间", "timestamp", false, false, false),
                                        col("updated_at", "更新时间", "timestamp", false, false, false)
                                ),
                                List.of("status", "message")),
                        editable("style_knowledge", "风格知识", "Agent 检索使用的调色知识",
                                "id", "updated_at",
                                List.of(
                                        col("id", "ID", "number", true, true, false),
                                        col("style_id", "风格 ID", "number", true, false, false),
                                        col("sample_id", "样片 ID", "number", true, false, false),
                                        col("title", "标题", "text", true, true, false),
                                        col("scene", "场景", "text", true, false, false),
                                        col("target_style", "目标风格", "text", true, false, false),
                                        col("problems_json", "问题 JSON", "json", true, false, false),
                                        col("strategy_json", "策略 JSON", "json", true, false, false),
                                        col("param_ranges_json", "参数范围 JSON", "json", true, false, false),
                                        col("content", "正文", "longText", true, false, false),
                                        col("embedding_id", "向量 ID", "text", true, false, false),
                                        col("status", "状态", "text", true, false, false),
                                        col("created_at", "创建时间", "timestamp", true, false, false),
                                        col("updated_at", "更新时间", "timestamp", true, false, false)
                                ),
                                List.of("title", "scene", "target_style", "content")),
                        readonly("knowledge_chunk", "知识分片", "RAG 检索分片，通常由系统生成",
                                "created_at",
                                List.of(
                                        col("id", "ID", "number", false, true, false),
                                        col("source_type", "来源类型", "text", false, true, false),
                                        col("source_id", "来源 ID", "number", false, true, false),
                                        col("title", "标题", "text", false, false, false),
                                        col("content", "内容", "longText", false, true, false),
                                        col("embedding_json", "向量 JSON", "json", false, false, false),
                                        col("created_at", "创建时间", "timestamp", false, false, false)
                                ),
                                List.of("title", "content"))
                )),
                new TableGroup("runtime", "运行时数据", "本地运行时用户、设备和事件流", List.of(
                        editable("runtime_user", "运行时用户", "本地运行时注册出的用户概念",
                                "id", "updated_at",
                                List.of(
                                        col("id", "用户 ID", "text", true, true, false),
                                        col("display_name", "显示名称", "text", true, false, false),
                                        col("source", "来源", "text", true, false, false),
                                        col("created_at", "创建时间", "timestamp", true, false, false),
                                        col("updated_at", "更新时间", "timestamp", true, false, false)
                                ),
                                List.of("id", "display_name", "source")),
                        editable("runtime_device", "运行时设备", "Lightroom 插件对应的本地运行时设备",
                                "id", "updated_at",
                                List.of(
                                        col("id", "设备 ID", "text", true, true, false),
                                        col("user_id", "用户 ID", "text", true, true, false),
                                        col("fingerprint", "设备指纹", "text", true, true, false),
                                        col("device_name", "设备名称", "text", true, false, false),
                                        col("endpoint", "访问地址", "text", true, false, false),
                                        col("metadata_json", "元数据 JSON", "json", true, false, false),
                                        col("last_seen_at", "最后在线", "timestamp", true, false, false),
                                        col("created_at", "创建时间", "timestamp", true, false, false),
                                        col("updated_at", "更新时间", "timestamp", true, false, false)
                                ),
                                List.of("id", "user_id", "fingerprint", "device_name")),
                        readonly("runtime_event", "运行事件", "用户输入、Agent 事件和工具调用事件流",
                                "created_at",
                                List.of(
                                        col("id", "事件 ID", "text", false, true, false),
                                        col("user_id", "用户 ID", "text", false, true, false),
                                        col("device_id", "设备 ID", "text", false, true, false),
                                        col("event_type", "事件类型", "text", false, false, false),
                                        col("session_id", "会话 ID", "text", false, false, false),
                                        col("payload_json", "事件内容 JSON", "json", false, false, false),
                                        col("created_at", "创建时间", "timestamp", false, false, false)
                                ),
                                List.of("user_id", "device_id", "event_type", "session_id", "payload_json"))
                )),
                new TableGroup("observability", "可观测数据", "模型调用、审计事件和 Agent 快照", List.of(
                        readonly("llm_call_log", "LLM 调用日志", "大模型调用输入输出摘要和耗时",
                                "started_at",
                                List.of(
                                        col("id", "ID", "text", false, true, false),
                                        col("run_id", "运行 ID", "text", false, false, false),
                                        col("provider", "模型厂商", "text", false, false, false),
                                        col("model_name", "模型", "text", false, false, false),
                                        col("task_type", "任务类型", "text", false, false, false),
                                        col("success", "是否成功", "boolean", false, false, false),
                                        col("latency_ms", "耗时毫秒", "number", false, false, false),
                                        col("prompt_chars", "提示词长度", "number", false, false, false),
                                        col("response_chars", "回复长度", "number", false, false, false),
                                        col("prompt_preview", "提示词摘要", "longText", false, false, false),
                                        col("response_preview", "回复摘要", "longText", false, false, false),
                                        col("error_message", "错误信息", "longText", false, false, false),
                                        col("started_at", "开始时间", "timestamp", false, false, false),
                                        col("finished_at", "结束时间", "timestamp", false, false, false)
                                ),
                                List.of("run_id", "provider", "model_name", "task_type", "prompt_preview", "response_preview", "error_message")),
                        readonly("audit_event", "审计事件", "系统行为审计记录",
                                "created_at",
                                List.of(
                                        col("id", "ID", "text", false, true, false),
                                        col("event_type", "事件类型", "text", false, false, false),
                                        col("actor", "操作者", "text", false, false, false),
                                        col("run_id", "运行 ID", "text", false, false, false),
                                        col("target_type", "目标类型", "text", false, false, false),
                                        col("target_id", "目标 ID", "text", false, false, false),
                                        col("detail", "详情", "longText", false, false, false),
                                        col("created_at", "创建时间", "timestamp", false, false, false)
                                ),
                                List.of("event_type", "actor", "run_id", "target_type", "target_id", "detail")),
                        readonly("workflow_run_snapshot", "Agent 快照", "Agent 编排运行快照",
                                "updated_at",
                                List.of(
                                        col("run_id", "运行 ID", "text", false, true, false),
                                        col("photo_id", "照片 ID", "number", false, false, false),
                                        col("status", "状态", "text", false, false, false),
                                        col("provider", "模型厂商", "text", false, false, false),
                                        col("target_style", "目标风格", "text", false, false, false),
                                        col("current_agent", "当前 Agent", "text", false, false, false),
                                        col("storage", "存储方式", "text", false, false, false),
                                        col("snapshot_json", "快照 JSON", "json", false, true, false),
                                        col("created_at", "创建时间", "timestamp", false, false, false),
                                        col("updated_at", "更新时间", "timestamp", false, false, false)
                                ),
                                List.of("run_id", "status", "provider", "target_style", "current_agent", "snapshot_json"))
                ))
        );
    }

    private TableDefinition editable(
            String tableName,
            String label,
            String description,
            String primaryKey,
            String orderColumn,
            List<ColumnDefinition> columns,
            List<String> searchableColumns
    ) {
        return new TableDefinition(tableName, label, description, true, primaryKey, orderColumn, columns, searchableColumns);
    }

    private TableDefinition readonly(
            String tableName,
            String label,
            String description,
            String orderColumn,
            List<ColumnDefinition> columns,
            List<String> searchableColumns
    ) {
        Optional<ColumnDefinition> primaryKey = columns.stream().filter(ColumnDefinition::primaryKey).findFirst();
        return new TableDefinition(tableName, label, description, false, primaryKey.map(ColumnDefinition::name).orElse(""), orderColumn, columns, searchableColumns);
    }

    private ColumnDefinition col(String name, String label, String type, boolean editable, boolean required, boolean primaryKey) {
        return new ColumnDefinition(name, label, type, editable, required, primaryKey);
    }

    public record TableGroup(String id, String label, String description, List<TableDefinition> children) {
    }

    public record TableDefinition(
            String tableName,
            String label,
            String description,
            boolean editable,
            String primaryKey,
            String orderColumn,
            List<ColumnDefinition> columns,
            List<String> searchableColumns
    ) {
        public ColumnDefinition column(String name) {
            return columns.stream()
                    .filter(column -> column.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        public boolean hasColumn(String name) {
            return column(name) != null;
        }
    }

    public record ColumnDefinition(
            String name,
            String label,
            String type,
            boolean editable,
            boolean required,
            boolean primaryKey
    ) {
    }

    public record TableRows(List<Map<String, Object>> rows, long total, int page, int size) {
    }
}
