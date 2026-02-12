package com.kiro.gateway.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 追踪日志 DAO
 */
@Component
public class TraceDAO {

    private final JdbcTemplate jdbc;

    public TraceDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String traceId, String apiType, String model, String accountId,
                       long durationMs, boolean success,
                       String clientRequest, String clientHeaders,
                       String kiroRequest, String kiroEndpoint, String kiroHeaders,
                       Integer kiroStatus, String kiroEvents,
                       int inputTokens, int outputTokens, double credits,
                       String clientResponse, Integer clientStatus, String errorMessage) {
        String now = Instant.now().toString();
        jdbc.update("""
                        INSERT OR REPLACE INTO traces (trace_id, timestamp, api_type, model, account_id,
                            duration_ms, success, client_request, client_headers, kiro_request, kiro_endpoint, kiro_headers,
                            kiro_status, kiro_events, input_tokens, output_tokens, credits,
                            client_response, client_status, error_message, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                traceId, now, apiType, model, accountId,
                durationMs, success ? 1 : 0,
                clientRequest, clientHeaders,
                kiroRequest, kiroEndpoint, kiroHeaders,
                kiroStatus, kiroEvents,
                inputTokens, outputTokens, credits,
                clientResponse, clientStatus, errorMessage, now);
    }

    public TraceRow findByTraceId(String traceId) {
        List<TraceRow> list = jdbc.query(
                "SELECT * FROM traces WHERE trace_id = ?",
                TRACE_ROW_MAPPER, traceId);
        return list.isEmpty() ? null : list.get(0);
    }

    public int cleanup(int retention) {
        return jdbc.update(
                "DELETE FROM traces WHERE id NOT IN (SELECT id FROM traces ORDER BY id DESC LIMIT ?)",
                retention);
    }

    private static final RowMapper<TraceRow> TRACE_ROW_MAPPER = (rs, rowNum) -> new TraceRow(
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

    public record TraceRow(int id, String traceId, String timestamp, String apiType, String model,
                            String accountId, long durationMs, boolean success,
                            String clientRequest, String clientHeaders,
                            String kiroRequest, String kiroEndpoint, String kiroHeaders,
                            Integer kiroStatus, String kiroEvents,
                            int inputTokens, int outputTokens, double credits,
                            String clientResponse, Integer clientStatus, String errorMessage) {}
}
