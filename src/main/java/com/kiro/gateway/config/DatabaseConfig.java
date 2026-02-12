package com.kiro.gateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * SQLite 数据库初始化（schema + 默认数据）
 */
@Component
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private final AppProperties properties;
    private final JdbcTemplate jdbc;

    public DatabaseConfig(AppProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        String dbPath = properties.getDatabase().getPath();
        Path parent = Path.of(dbPath).getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new RuntimeException("无法创建数据库目录: " + parent, e);
            }
        }

        initSchema();
        initDefaults();

        log.info("SQLite 数据库初始化完成: {}", dbPath);
    }

    private void initSchema() {
        try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
            if (is == null) {
                throw new RuntimeException("未找到 schema.sql");
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            for (String s : sql.split(";")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    jdbc.execute(trimmed);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("初始化数据库 schema 失败", e);
        }
    }

    private void initDefaults() {
        String now = Instant.now().toString();

        // 初始化设置（如不存在）
        jdbc.update(
                "INSERT OR IGNORE INTO settings (id, admin_password, pool_strategy, created_at, updated_at) VALUES (1, ?, ?, ?, ?)",
                properties.getAdminPassword(), properties.getPoolStrategy(), now, now);

        // 初始化默认 API Key（如不存在）
        jdbc.update(
                "INSERT OR IGNORE INTO api_keys (key, name, created_at) VALUES (?, ?, ?)",
                properties.getApiKey(), "默认密钥", now);

        // 初始化默认模型
        initDefaultModels(now);
        initDefaultMappings(now);
    }

    private void initDefaultModels(String now) {
        String[][] models = {
                {"auto-kiro", "Auto (Kiro)", "32000"},
                {"claude-sonnet-4.5", "Claude Sonnet 4.5", "32000"},
                {"claude-sonnet-4", "Claude Sonnet 4", "32000"},
                {"claude-haiku-4.5", "Claude Haiku 4.5", "32000"},
                {"claude-opus-4.5", "Claude Opus 4.5", "32000"},
                {"claude-opus-4.6", "Claude Opus 4.6", "32000"},
                {"claude-3.7-sonnet", "Claude 3.7 Sonnet", "32000"},
        };
        for (String[] m : models) {
            jdbc.update(
                    "INSERT OR IGNORE INTO models (id, display_name, max_tokens, created_at) VALUES (?, ?, ?, ?)",
                    m[0], m[1], Integer.parseInt(m[2]), now);
        }
    }

    private void initDefaultMappings(String now) {
        String[][] mappings = {
                // 精确映射（高优先级）
                {"auto", "auto-kiro", "exact", "100"},
                {"claude-sonnet-4-5-20250929", "claude-sonnet-4.5", "exact", "100"},
                {"claude-haiku-4-5-20251001", "claude-haiku-4.5", "exact", "100"},
                // 模糊匹配
                {"opus-4.6", "claude-opus-4.6", "contains", "15"},
                {"opus-4.5", "claude-opus-4.5", "contains", "15"},
                {"sonnet-4.5", "claude-sonnet-4.5", "contains", "12"},
                {"sonnet-4", "claude-sonnet-4", "contains", "11"},
                {"haiku", "claude-haiku-4.5", "contains", "10"},
                {"opus", "claude-opus-4.5", "contains", "5"},
                {"sonnet", "claude-sonnet-4.5", "contains", "5"},
                {"3.7-sonnet", "claude-3.7-sonnet", "contains", "15"},
                {"3-7-sonnet", "claude-3.7-sonnet", "contains", "15"},
                {"claude-3-5-sonnet", "claude-sonnet-4.5", "contains", "8"},
                {"claude-3-5-haiku", "claude-haiku-4.5", "contains", "8"},
                // GPT 兼容映射
                {"gpt-4o", "claude-sonnet-4.5", "contains", "3"},
                {"gpt-4", "claude-sonnet-4.5", "contains", "2"},
                {"gpt-3.5", "claude-haiku-4.5", "contains", "2"},
        };
        for (String[] m : mappings) {
            jdbc.update(
                    "INSERT OR IGNORE INTO model_mappings (external_pattern, internal_id, match_type, priority, created_at) VALUES (?, ?, ?, ?, ?)",
                    m[0], m[1], m[2], Integer.parseInt(m[3]), now);
        }
    }
}
