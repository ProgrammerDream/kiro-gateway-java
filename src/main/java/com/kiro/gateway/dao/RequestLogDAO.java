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
                       String apiKey, boolean stream, String endpoint,
                       String conversationId) {
        String now = Instant.now().toString();
        jdbc.update("""
                        INSERT INTO request_logs (timestamp, trace_id, api_type, model, account_id, account_name,
                            input_tokens, output_tokens, credits, duration_ms, success, error_message, api_key, stream, endpoint, conversation_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                now, traceId, apiType, model, accountId, accountName,
                inputTokens, outputTokens, credits, durationMs,
                success ? 1 : 0, errorMessage, apiKey, stream ? 1 : 0, endpoint, conversationId, now);
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

    /**
     * 按 conversation_id 分组查询会话列表
     */
    public List<ConversationSummary> findConversations(int limit, int offset) {
        return jdbc.query("""
                        SELECT conversation_id,
                               COUNT(*) AS rounds,
                               MIN(timestamp) AS first_time,
                               MAX(timestamp) AS last_time,
                               MAX(model) AS model,
                               MAX(account_name) AS account_name,
                               SUM(input_tokens) AS total_input,
                               SUM(output_tokens) AS total_output,
                               SUM(credits) AS total_credits,
                               MIN(CASE WHEN success = 0 THEN 0 ELSE 1 END) AS all_success
                        FROM request_logs
                        WHERE conversation_id IS NOT NULL
                        GROUP BY conversation_id
                        ORDER BY MAX(id) DESC
                        LIMIT ? OFFSET ?
                        """,
                CONVERSATION_SUMMARY_MAPPER, limit, offset);
    }

    /**
     * 会话数量（去重）
     */
    public int countConversations() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT conversation_id) FROM request_logs WHERE conversation_id IS NOT NULL",
                Integer.class);
        return c != null ? c : 0;
    }

    /**
     * 查询某个会话下的所有请求
     */
    public List<RequestLogRow> findByConversationId(String conversationId) {
        return jdbc.query(
                "SELECT * FROM request_logs WHERE conversation_id = ? ORDER BY id ASC",
                REQUEST_LOG_ROW_MAPPER, conversationId);
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
            rs.getString("api_key"), rs.getInt("stream") == 1, rs.getString("endpoint"),
            rs.getString("conversation_id")
    );

    private static final RowMapper<ConversationSummary> CONVERSATION_SUMMARY_MAPPER = (rs, rowNum) ->
            new ConversationSummary(
                    rs.getString("conversation_id"),
                    rs.getInt("rounds"),
                    rs.getString("first_time"),
                    rs.getString("last_time"),
                    rs.getString("model"),
                    rs.getString("account_name"),
                    rs.getInt("total_input"),
                    rs.getInt("total_output"),
                    rs.getDouble("total_credits"),
                    rs.getInt("all_success") == 1
            );

    public record RequestLogRow(int id, String timestamp, String traceId, String apiType, String model,
                                 String accountId, String accountName, int inputTokens, int outputTokens,
                                 double credits, long durationMs, boolean success, String errorMessage,
                                 String apiKey, boolean stream, String endpoint, String conversationId) {}

    public record ConversationSummary(String conversationId, int rounds,
                                      String firstTime, String lastTime,
                                      String model, String accountName,
                                      int totalInput, int totalOutput,
                                      double totalCredits, boolean allSuccess) {}
}
