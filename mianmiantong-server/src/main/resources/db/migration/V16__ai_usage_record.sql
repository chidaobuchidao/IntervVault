-- occurred_at is stored as Asia/Shanghai local time by the application.
CREATE TABLE ai_usage_record (
    request_id VARCHAR(36) PRIMARY KEY,
    occurred_at DATETIME NOT NULL,
    user_id BIGINT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(255) NOT NULL,
    feature VARCHAR(64) NOT NULL,
    key_source VARCHAR(16) NOT NULL,
    streaming BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    CONSTRAINT ck_usage_input CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT ck_usage_output CHECK (output_tokens IS NULL OR output_tokens >= 0)
);
CREATE INDEX idx_usage_time ON ai_usage_record (occurred_at);
CREATE INDEX idx_usage_user_time ON ai_usage_record (user_id, occurred_at);
