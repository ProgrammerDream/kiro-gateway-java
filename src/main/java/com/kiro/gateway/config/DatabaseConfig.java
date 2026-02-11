package com.kiro.gateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据库配置与 DAO
 */
@Component
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private final AppProperties properties;
    private String jdbcUrl;

    public DatabaseConfig(AppProperties properties) {
        this.properties = properties;
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

        this.jdbcUrl = "jdbc:sqlite:" + dbPath;

        // 初始化表结构
        initSchema();
        // 初始化默认数据
        initDefaults();

        log.info("SQLite 数据库初始化完成: {}", dbPath);
    }

    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        // 启用 WAL 模式
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    private void initSchema() {
        try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
            if (is == null) {
                throw new RuntimeException("未找到 schema.sql");
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // 按分号分割执行每条 SQL
                for (String s : sql.split(";")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }
        } catch (IOException | SQLException e) {
            throw new RuntimeException("初始化数据库 schema 失败", e);
        }
    }

    private void initDefaults() {
        String now = Instant.now().toString();
        try (Connection conn = getConnection()) {
            // 初始化设置（如不存在）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO settings (id, admin_password, pool_strategy, created_at, updated_at) VALUES (1, ?, ?, ?, ?)")) {
                ps.setString(1, properties.getAdminPassword());
                ps.setString(2, properties.getPoolStrategy());
                ps.setString(3, now);
                ps.setString(4, now);
                ps.executeUpdate();
            }

            // 初始化默认 API Key（如不存在）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO api_keys (key, name, created_at) VALUES (?, ?, ?)")) {
                ps.setString(1, properties.getApiKey());
                ps.setString(2, "默认密钥");
                ps.setString(3, now);
                ps.executeUpdate();
            }

            // 初始化默认模型（如不存在）
            initDefaultModels(conn, now);
            initDefaultMappings(conn, now);
        } catch (SQLException e) {
            throw new RuntimeException("初始化默认数据失败", e);
        }
    }

    private void initDefaultModels(Connection conn, String now) throws SQLException {
        String[][] models = {
                {"claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", "32000"},
                {"claude-haiku-4-5-20251001", "Claude Haiku 4.5", "32000"},
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO models (id, display_name, max_tokens, created_at) VALUES (?, ?, ?, ?)")) {
            for (String[] m : models) {
                ps.setString(1, m[0]);
                ps.setString(2, m[1]);
                ps.setInt(3, Integer.parseInt(m[2]));
                ps.setString(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void initDefaultMappings(Connection conn, String now) throws SQLException {
        String[][] mappings = {
                {"sonnet", "claude-sonnet-4.5", "contains", "10"},
                {"haiku", "claude-haiku-4.5", "contains", "10"},
                {"opus", "claude-sonnet-4.5", "contains", "5"},
                {"claude-3-5-sonnet", "claude-sonnet-4.5", "contains", "8"},
                {"claude-3-5-haiku", "claude-haiku-4.5", "contains", "8"},
                {"gpt-4o", "claude-sonnet-4.5", "contains", "3"},
                {"gpt-4", "claude-sonnet-4.5", "contains", "2"},
                {"gpt-3.5", "claude-haiku-4.5", "contains", "2"},
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO model_mappings (external_pattern, internal_id, match_type, priority, created_at) VALUES (?, ?, ?, ?, ?)")) {
            for (String[] m : mappings) {
                ps.setString(1, m[0]);
                ps.setString(2, m[1]);
                ps.setString(3, m[2]);
                ps.setInt(4, Integer.parseInt(m[3]));
                ps.setString(5, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ==================== DAO 方法 ====================

    /**
     * 插入请求日志
     */
    public void insertRequestLog(String traceId, String apiType, String model,
                                  String accountId, String accountName,
                                  int inputTokens, int outputTokens, double credits,
                                  long durationMs, boolean success, String errorMessage,
                                  String apiKey, boolean stream, String endpoint) {
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO request_logs (timestamp, trace_id, api_type, model, account_id, account_name,
                    input_tokens, output_tokens, credits, duration_ms, success, error_message, api_key, stream, endpoint, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, now);
            ps.setString(2, traceId);
            ps.setString(3, apiType);
            ps.setString(4, model);
            ps.setString(5, accountId);
            ps.setString(6, accountName);
            ps.setInt(7, inputTokens);
            ps.setInt(8, outputTokens);
            ps.setDouble(9, credits);
            ps.setLong(10, durationMs);
            ps.setInt(11, success ? 1 : 0);
            ps.setString(12, errorMessage);
            ps.setString(13, apiKey);
            ps.setInt(14, stream ? 1 : 0);
            ps.setString(15, endpoint);
            ps.setString(16, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("插入请求日志失败", e);
        }
    }

    /**
     * 查询请求日志（分页）
     */
    public List<RequestLogRow> getRequestLogs(int limit, int offset) {
        String sql = "SELECT * FROM request_logs ORDER BY id DESC LIMIT ? OFFSET ?";
        List<RequestLogRow> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRequestLog(rs));
                }
            }
        } catch (SQLException e) {
            log.error("查询请求日志失败", e);
        }
        return list;
    }

    /**
     * 查询请求日志总数
     */
    public int getRequestLogCount() {
        String sql = "SELECT COUNT(*) FROM request_logs";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("查询请求日志总数失败", e);
        }
        return 0;
    }

    /**
     * 清理旧的请求日志
     */
    public int cleanupRequestLogs(int retention) {
        String sql = "DELETE FROM request_logs WHERE id NOT IN (SELECT id FROM request_logs ORDER BY id DESC LIMIT ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, retention);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("清理请求日志失败", e);
            return 0;
        }
    }

    /**
     * 清理旧的追踪日志
     */
    public int cleanupTraces(int retention) {
        String sql = "DELETE FROM traces WHERE id NOT IN (SELECT id FROM traces ORDER BY id DESC LIMIT ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, retention);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.error("清理追踪日志失败", e);
            return 0;
        }
    }

    /**
     * 插入追踪日志
     */
    public void insertTrace(String traceId, String apiType, String model, String accountId,
                             long durationMs, boolean success,
                             String clientRequest, String clientHeaders,
                             String kiroRequest, String kiroEndpoint, String kiroHeaders,
                             Integer kiroStatus, String kiroEvents,
                             int inputTokens, int outputTokens, double credits,
                             String clientResponse, Integer clientStatus, String errorMessage) {
        String now = Instant.now().toString();
        String sql = """
                INSERT OR REPLACE INTO traces (trace_id, timestamp, api_type, model, account_id,
                    duration_ms, success, client_request, client_headers, kiro_request, kiro_endpoint, kiro_headers,
                    kiro_status, kiro_events, input_tokens, output_tokens, credits,
                    client_response, client_status, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            ps.setString(2, now);
            ps.setString(3, apiType);
            ps.setString(4, model);
            ps.setString(5, accountId);
            ps.setLong(6, durationMs);
            ps.setInt(7, success ? 1 : 0);
            ps.setString(8, clientRequest);
            ps.setString(9, clientHeaders);
            ps.setString(10, kiroRequest);
            ps.setString(11, kiroEndpoint);
            ps.setString(12, kiroHeaders);
            if (kiroStatus != null) ps.setInt(13, kiroStatus); else ps.setNull(13, java.sql.Types.INTEGER);
            ps.setString(14, kiroEvents);
            ps.setInt(15, inputTokens);
            ps.setInt(16, outputTokens);
            ps.setDouble(17, credits);
            ps.setString(18, clientResponse);
            if (clientStatus != null) ps.setInt(19, clientStatus); else ps.setNull(19, java.sql.Types.INTEGER);
            ps.setString(20, errorMessage);
            ps.setString(21, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("插入追踪日志失败", e);
        }
    }

    /**
     * 查询追踪详情
     */
    public TraceRow getTraceByTraceId(String traceId) {
        String sql = "SELECT * FROM traces WHERE trace_id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapTrace(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询追踪详情失败", e);
        }
        return null;
    }

    // ==================== 账号 DAO ====================

    /**
     * 获取所有账号
     */
    public List<AccountRow> getAllAccounts() {
        String sql = "SELECT * FROM accounts ORDER BY created_at";
        List<AccountRow> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapAccount(rs));
            }
        } catch (SQLException e) {
            log.error("查询账号列表失败", e);
        }
        return list;
    }

    /**
     * 插入账号
     */
    public void insertAccount(String id, String name, String credentials, String authMethod) {
        String now = Instant.now().toString();
        String sql = "INSERT INTO accounts (id, name, credentials, auth_method, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'active', ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, credentials);
            ps.setString(4, authMethod);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("插入账号失败", e);
        }
    }

    /**
     * 删除账号
     */
    public void deleteAccount(String id) {
        String sql = "DELETE FROM accounts WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("删除账号失败", e);
        }
    }

    /**
     * 更新账号基本信息
     */
    public void updateAccountInfo(String id, String name, String credentials, String authMethod) {
        String sql = "UPDATE accounts SET name = ?, credentials = ?, auth_method = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, credentials);
            ps.setString(3, authMethod);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("更新账号信息失败", e);
        }
    }

    /**
     * 更新账号状态
     */
    public void updateAccountStatus(String id, String status) {
        String sql = "UPDATE accounts SET status = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("更新账号状态失败", e);
        }
    }

    /**
     * 更新账号统计
     */
    public void updateAccountStats(String id, int requestCount, int successCount, int errorCount,
                                    int consecutiveErrors, long inputTokens, long outputTokens,
                                    double credits, String cooldownUntil, String lastUsedAt) {
        String sql = """
                UPDATE accounts SET request_count = ?, success_count = ?, error_count = ?,
                    consecutive_errors = ?, input_tokens_total = ?, output_tokens_total = ?,
                    credits_total = ?, cooldown_until = ?, last_used_at = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, requestCount);
            ps.setInt(2, successCount);
            ps.setInt(3, errorCount);
            ps.setInt(4, consecutiveErrors);
            ps.setLong(5, inputTokens);
            ps.setLong(6, outputTokens);
            ps.setDouble(7, credits);
            ps.setString(8, cooldownUntil);
            ps.setString(9, lastUsedAt);
            ps.setString(10, Instant.now().toString());
            ps.setString(11, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("更新账号统计失败", e);
        }
    }

    // ==================== API Key DAO ====================

    public List<ApiKeyRow> getAllApiKeys() {
        String sql = "SELECT * FROM api_keys ORDER BY created_at";
        List<ApiKeyRow> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ApiKeyRow(rs.getString("key"), rs.getString("name"), rs.getString("created_at")));
            }
        } catch (SQLException e) {
            log.error("查询 API Key 列表失败", e);
        }
        return list;
    }

    public boolean validateApiKey(String key) {
        String sql = "SELECT COUNT(*) FROM api_keys WHERE key = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("验证 API Key 失败", e);
            return false;
        }
    }

    // ==================== Row 映射 ====================

    private RequestLogRow mapRequestLog(ResultSet rs) throws SQLException {
        return new RequestLogRow(
                rs.getInt("id"), rs.getString("timestamp"), rs.getString("trace_id"),
                rs.getString("api_type"), rs.getString("model"),
                rs.getString("account_id"), rs.getString("account_name"),
                rs.getInt("input_tokens"), rs.getInt("output_tokens"),
                rs.getDouble("credits"), rs.getLong("duration_ms"),
                rs.getInt("success") == 1, rs.getString("error_message"),
                rs.getString("api_key"), rs.getInt("stream") == 1, rs.getString("endpoint")
        );
    }

    private TraceRow mapTrace(ResultSet rs) throws SQLException {
        return new TraceRow(
                rs.getInt("id"), rs.getString("trace_id"), rs.getString("timestamp"),
                rs.getString("api_type"), rs.getString("model"), rs.getString("account_id"),
                rs.getLong("duration_ms"), rs.getInt("success") == 1,
                rs.getString("client_request"), rs.getString("client_headers"),
                rs.getString("kiro_request"), rs.getString("kiro_endpoint"), rs.getString("kiro_headers"),
                rs.getObject("kiro_status") != null ? rs.getInt("kiro_status") : null,
                rs.getString("kiro_events"),
                rs.getInt("input_tokens"), rs.getInt("output_tokens"), rs.getDouble("credits"),
                rs.getString("client_response"),
                rs.getObject("client_status") != null ? rs.getInt("client_status") : null,
                rs.getString("error_message")
        );
    }

    private AccountRow mapAccount(ResultSet rs) throws SQLException {
        return new AccountRow(
                rs.getString("id"), rs.getString("name"), rs.getString("credentials"),
                rs.getString("auth_method"), rs.getString("status"),
                rs.getInt("request_count"), rs.getInt("success_count"), rs.getInt("error_count"),
                rs.getInt("consecutive_errors"),
                rs.getLong("input_tokens_total"), rs.getLong("output_tokens_total"),
                rs.getDouble("credits_total"),
                rs.getString("cooldown_until"), rs.getString("last_used_at"),
                rs.getString("created_at"), rs.getString("updated_at")
        );
    }

    // ==================== Record 定义 ====================

    public record RequestLogRow(int id, String timestamp, String traceId, String apiType, String model,
                                 String accountId, String accountName, int inputTokens, int outputTokens,
                                 double credits, long durationMs, boolean success, String errorMessage,
                                 String apiKey, boolean stream, String endpoint) {}

    public record TraceRow(int id, String traceId, String timestamp, String apiType, String model,
                            String accountId, long durationMs, boolean success,
                            String clientRequest, String clientHeaders,
                            String kiroRequest, String kiroEndpoint, String kiroHeaders,
                            Integer kiroStatus, String kiroEvents,
                            int inputTokens, int outputTokens, double credits,
                            String clientResponse, Integer clientStatus, String errorMessage) {}

    public record AccountRow(String id, String name, String credentials, String authMethod, String status,
                              int requestCount, int successCount, int errorCount, int consecutiveErrors,
                              long inputTokensTotal, long outputTokensTotal, double creditsTotal,
                              String cooldownUntil, String lastUsedAt,
                              String createdAt, String updatedAt) {}

    public record ApiKeyRow(String key, String name, String createdAt) {}
}
