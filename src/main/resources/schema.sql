-- 账号表
CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    credentials TEXT NOT NULL,
    auth_method TEXT NOT NULL DEFAULT 'social',
    status TEXT NOT NULL DEFAULT 'active',
    request_count INTEGER DEFAULT 0,
    success_count INTEGER DEFAULT 0,
    error_count INTEGER DEFAULT 0,
    consecutive_errors INTEGER DEFAULT 0,
    input_tokens_total INTEGER DEFAULT 0,
    output_tokens_total INTEGER DEFAULT 0,
    credits_total REAL DEFAULT 0,
    cooldown_until TEXT,
    last_used_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts(status);

-- 系统设置表（单例）
CREATE TABLE IF NOT EXISTS settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    admin_password TEXT NOT NULL,
    pool_strategy TEXT NOT NULL DEFAULT 'round-robin',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- API 密钥表
CREATE TABLE IF NOT EXISTS api_keys (
    key TEXT PRIMARY KEY,
    name TEXT,
    created_at TEXT NOT NULL
);

-- 模型表
CREATE TABLE IF NOT EXISTS models (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    max_tokens INTEGER DEFAULT 32000,
    owned_by TEXT DEFAULT 'anthropic',
    enabled INTEGER DEFAULT 1,
    display_order INTEGER DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_models_enabled ON models(enabled);

-- 模型映射表
CREATE TABLE IF NOT EXISTS model_mappings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    external_pattern TEXT NOT NULL UNIQUE,
    internal_id TEXT NOT NULL,
    match_type TEXT DEFAULT 'contains',
    priority INTEGER DEFAULT 0,
    enabled INTEGER DEFAULT 1,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_model_mappings_priority ON model_mappings(priority DESC);

-- 请求日志表
CREATE TABLE IF NOT EXISTS request_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    api_type TEXT NOT NULL,
    model TEXT,
    account_id TEXT,
    account_name TEXT,
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    credits REAL DEFAULT 0,
    duration_ms INTEGER DEFAULT 0,
    success INTEGER DEFAULT 1,
    error_message TEXT,
    api_key TEXT,
    stream INTEGER DEFAULT 0,
    endpoint TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_request_logs_timestamp ON request_logs(timestamp);
CREATE INDEX IF NOT EXISTS idx_request_logs_trace_id ON request_logs(trace_id);
CREATE INDEX IF NOT EXISTS idx_request_logs_model ON request_logs(model);
CREATE INDEX IF NOT EXISTS idx_request_logs_success ON request_logs(success);

-- 追踪日志表
CREATE TABLE IF NOT EXISTS traces (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trace_id TEXT NOT NULL UNIQUE,
    timestamp TEXT NOT NULL,
    api_type TEXT NOT NULL,
    model TEXT,
    account_id TEXT,
    duration_ms INTEGER DEFAULT 0,
    success INTEGER DEFAULT 1,
    client_request TEXT,
    client_headers TEXT,
    kiro_request TEXT,
    kiro_endpoint TEXT,
    kiro_headers TEXT,
    kiro_status INTEGER,
    kiro_events TEXT,
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    credits REAL DEFAULT 0,
    client_response TEXT,
    client_status INTEGER,
    error_message TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_traces_timestamp ON traces(timestamp);
CREATE INDEX IF NOT EXISTS idx_traces_trace_id ON traces(trace_id);
CREATE INDEX IF NOT EXISTS idx_traces_api_type ON traces(api_type);
CREATE INDEX IF NOT EXISTS idx_traces_model ON traces(model);

-- Metrics 表
CREATE TABLE IF NOT EXISTS metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    endpoint TEXT,
    status_code INTEGER,
    model TEXT,
    latency_ms INTEGER DEFAULT 0,
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_metrics_timestamp ON metrics(timestamp);
