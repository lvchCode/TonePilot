CREATE TABLE IF NOT EXISTS workflow_run_snapshot (
    run_id VARCHAR(64) PRIMARY KEY,
    photo_id BIGINT,
    status VARCHAR(64),
    provider VARCHAR(64),
    target_style VARCHAR(255),
    current_agent VARCHAR(128),
    storage VARCHAR(64),
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS llm_call_log (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64),
    provider VARCHAR(64),
    model_name VARCHAR(128),
    task_type VARCHAR(64),
    success BOOLEAN,
    latency_ms BIGINT,
    prompt_chars INT,
    response_chars INT,
    prompt_preview TEXT,
    response_preview TEXT,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_event (
    id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(128),
    actor VARCHAR(128),
    run_id VARCHAR(64),
    target_type VARCHAR(64),
    target_id VARCHAR(128),
    detail TEXT,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS domain_snapshot (
    domain_type VARCHAR(64) NOT NULL,
    domain_id VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (domain_type, domain_id)
);

CREATE TABLE IF NOT EXISTS runtime_user (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128),
    source VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS runtime_device (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(255) NOT NULL,
    device_name VARCHAR(128),
    endpoint VARCHAR(255),
    metadata_json TEXT,
    last_seen_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE KEY idx_runtime_device_fingerprint (fingerprint)
);

CREATE TABLE IF NOT EXISTS runtime_event (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128),
    session_id VARCHAR(128),
    payload_json TEXT,
    created_at TIMESTAMP,
    KEY idx_runtime_event_user (user_id, created_at)
);

CREATE INDEX idx_llm_call_log_started_at ON llm_call_log(started_at);
CREATE INDEX idx_audit_event_created_at ON audit_event(created_at);
CREATE INDEX idx_domain_snapshot_type ON domain_snapshot(domain_type);
CREATE TABLE IF NOT EXISTS knowledge_source (
    id BIGINT PRIMARY KEY,
    source_type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    author VARCHAR(128),
    original_url VARCHAR(1024),
    style_id BIGINT,
    notes TEXT,
    status VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    KEY idx_knowledge_source_type (source_type),
    KEY idx_knowledge_source_style (style_id),
    KEY idx_knowledge_source_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS knowledge_material (
    id BIGINT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    material_type VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    language VARCHAR(32),
    created_at TIMESTAMP,
    KEY idx_knowledge_material_source (source_id),
    KEY idx_knowledge_material_type (material_type)
);

CREATE TABLE IF NOT EXISTS knowledge_extraction_job (
    id BIGINT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    status VARCHAR(64),
    generated_knowledge_id BIGINT,
    message TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    KEY idx_knowledge_job_source (source_id),
    KEY idx_knowledge_job_material (material_id),
    KEY idx_knowledge_job_generated (generated_knowledge_id)
);

CREATE TABLE IF NOT EXISTS style_knowledge (
    id BIGINT PRIMARY KEY,
    style_id BIGINT,
    sample_id BIGINT,
    title VARCHAR(255) NOT NULL,
    scene VARCHAR(128),
    target_style VARCHAR(128),
    problems_json TEXT,
    strategy_json TEXT,
    param_ranges_json TEXT,
    content LONGTEXT,
    embedding_id VARCHAR(128),
    status VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    KEY idx_style_knowledge_status (status),
    KEY idx_style_knowledge_style (style_id),
    KEY idx_style_knowledge_scene (scene)
);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT PRIMARY KEY,
    source_type VARCHAR(64) NOT NULL,
    source_id BIGINT NOT NULL,
    title VARCHAR(255),
    content TEXT NOT NULL,
    embedding_json LONGTEXT,
    created_at TIMESTAMP,
    KEY idx_knowledge_chunk_source (source_type, source_id)
);
