-- H2 schema for testing (MySQL compatibility mode)
-- Combined from V1-V13 migrations

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(64) NOT NULL UNIQUE,
    nickname VARCHAR(50),
    avatar_url VARCHAR(255),
    role TINYINT DEFAULT 0,
    knowledge_base_enabled TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 题目分类表
CREATE TABLE IF NOT EXISTS question_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    icon VARCHAR(50),
    sort_order INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 题库表
CREATE TABLE IF NOT EXISTS question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    type TINYINT NOT NULL,
    title TEXT NOT NULL,
    options TEXT,
    answer VARCHAR(500) NOT NULL,
    analysis TEXT,
    difficulty TINYINT DEFAULT 1,
    tags VARCHAR(200),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 试卷表
CREATE TABLE IF NOT EXISTS exam (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    duration INT,
    total_score INT DEFAULT 100,
    status TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 试卷-题目关联表
CREATE TABLE IF NOT EXISTS exam_question (
    exam_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    sort_order INT DEFAULT 0,
    PRIMARY KEY (exam_id, question_id)
);

-- 答题记录表
CREATE TABLE IF NOT EXISTS answer_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    exam_id BIGINT,
    question_id BIGINT NOT NULL,
    user_answer VARCHAR(500),
    is_correct TINYINT,
    score INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 错题本
CREATE TABLE IF NOT EXISTS wrong_question (
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    wrong_count INT DEFAULT 1,
    last_wrong_time TIMESTAMP,
    PRIMARY KEY (user_id, question_id)
);

-- 用户收藏表
CREATE TABLE IF NOT EXISTS user_favorite (
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, question_id)
);

-- AI面试会话表
CREATE TABLE IF NOT EXISTS interview_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    position VARCHAR(50),
    messages TEXT,
    current_question_index INT DEFAULT 0,
    overall_score INT,
    dimensions TEXT,
    feedback TEXT,
    coding_score INT DEFAULT 0,
    coding_dimensions TEXT,
    coding_feedback TEXT,
    coding_suggestion TEXT,
    model VARCHAR(30) DEFAULT 'deepseek-v4-flash',
    status TINYINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finish_time TIMESTAMP
);

-- 用户AI配置表
CREATE TABLE IF NOT EXISTS user_ai_config (
    user_id BIGINT PRIMARY KEY,
    provider VARCHAR(20) NOT NULL DEFAULT 'deepseek',
    api_key VARCHAR(255) NOT NULL,
    model VARCHAR(30) DEFAULT 'deepseek-v4-flash',
    custom_endpoint VARCHAR(512) DEFAULT NULL,
    preferred_model VARCHAR(64) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 简历表
CREATE TABLE IF NOT EXISTS resume (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    file_path VARCHAR(500),
    file_data BLOB,
    parsed_text TEXT,
    job_description TEXT,
    parse_status TINYINT DEFAULT 0,
    doc_task_id VARCHAR(100),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 简历分析报告表
CREATE TABLE IF NOT EXISTS resume_analysis (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resume_id BIGINT NOT NULL UNIQUE,
    overall_score INT,
    dimensions TEXT,
    missing_keywords TEXT,
    optimized_text TEXT,
    highlights TEXT,
    interview_questions TEXT,
    suggestion TEXT,
    deep_status INT DEFAULT 0,
    retry_count INT DEFAULT 0,
    partial_response TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 简历模板表
CREATE TABLE IF NOT EXISTS resume_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    content TEXT,
    category VARCHAR(50),
    sort_order INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 公告表
CREATE TABLE IF NOT EXISTS announcement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    status TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 算法题表
CREATE TABLE IF NOT EXISTS algorithm_problem (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    difficulty TINYINT DEFAULT 1,
    category VARCHAR(50),
    description TEXT,
    examples TEXT,
    constraints TEXT,
    starter_code TEXT,
    solution TEXT,
    tags VARCHAR(200),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 论文知识库表
CREATE TABLE IF NOT EXISTS paper_knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    file_name VARCHAR(200),
    file_path VARCHAR(500),
    file_data BLOB,
    parsed_text TEXT,
    chunk_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户配额表
CREATE TABLE IF NOT EXISTS user_quota (
    user_id BIGINT PRIMARY KEY,
    daily_quota INT DEFAULT 10,
    quota_used INT DEFAULT 0,
    last_reset_date DATE,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- occurred_at is stored as Asia/Shanghai local time by the application.
CREATE TABLE IF NOT EXISTS ai_usage_record (
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
CREATE INDEX IF NOT EXISTS idx_usage_time ON ai_usage_record (occurred_at);
CREATE INDEX IF NOT EXISTS idx_usage_user_time ON ai_usage_record (user_id, occurred_at);
