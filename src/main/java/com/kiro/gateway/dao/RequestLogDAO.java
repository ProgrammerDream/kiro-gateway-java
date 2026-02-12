package com.kiro.gateway.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 请求日志 DAO
 */
@Component
public class RequestLogDAO {

    private final JdbcTemplate jdbc;

    public RequestLogDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String traceId, String apiType, String model,
                       String accountId, String accountName,
                       int inputTokens, int outputTokens, double credits,
                       long durationMs, boolean success, String errorMessage,
                       String apiKey, boolean stream, String endpoint) {
        String now = Instant.now().toString();
        jdbc.update("""
                        INSERT INTO request_logs (timestamp, trace_id, api_type, model, account_id, account_name,
                            input_tokens, output_tokens, credits, duration_ms, success, error_message, api_key, stream, endpoint, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                now, traceId, apiType, model, accountId, accountName,
                inputTokens, outputTokens, credits, durationMs,
                success ? 1 : 0, errorMessage, apiKey, stream ? 1 : 0, endpoint, now);
    }

    public List<RequestLogRow> findPage(int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM request_logs ORDER BY id DESC LIMIT ? OFFSET ?",
                REQUEST_LOG_ROW_MAPPER, limit, offset);
    }

    public int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM request_logs", Integer.class);
        return c != null ? c : 0;
    }

    public int cleanup(int retention) {
        return jdbc.update(
                "DELETE FROM request_logs WHERE id NOT IN (SELECT id FROM request_logs ORDER BY id DESC LIMIT ?)",
                retention);
    }

    private static final RowMapper<RequestLogRow> REQUEST_LOG_ROW_MAPPER = (rs, rowNum) -> new RequestLogRow(
            rs.getInt("id"), rs.getString("timestamp"), rs.getString("trace_id"),
            rs.getString("api_type"), rs.getString("model"),
            rs.getString("account_id"), rs.getString("account_name"),
            rs.getInt("input_tokens"), rs.getInt("output_tokens"),
            rs.getDouble("credits"), rs.getLong("duration_ms"),
            rs.getInt("success") == 1, rs.getString("error_message"),
            rs.getString("api_key"), rs.getInt("stream") == 1, rs.getString("endpoint")
    );

    public record RequestLogRow(int id, String timestamp, String traceId, String apiType, String model,
                                 String accountId, String accountName, int inputTokens, int outputTokens,
                                 double credits, long durationMs, boolean success, String errorMessage,
                                 String apiKey, boolean stream, String endpoint) {}
}
