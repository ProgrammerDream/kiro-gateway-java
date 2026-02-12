package com.kiro.gateway.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 账号 DAO
 */
@Component
public class AccountDAO {

    private final JdbcTemplate jdbc;

    public AccountDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AccountRow> findAll() {
        return jdbc.query("SELECT * FROM accounts ORDER BY created_at", ACCOUNT_ROW_MAPPER);
    }

    public void insert(String id, String name, String credentials, String authMethod) {
        String now = Instant.now().toString();
        jdbc.update(
                "INSERT INTO accounts (id, name, credentials, auth_method, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'active', ?, ?)",
                id, name, credentials, authMethod, now, now);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM accounts WHERE id = ?", id);
    }

    public void updateInfo(String id, String name, String credentials, String authMethod) {
        jdbc.update(
                "UPDATE accounts SET name = ?, credentials = ?, auth_method = ?, updated_at = ? WHERE id = ?",
                name, credentials, authMethod, Instant.now().toString(), id);
    }

    public void updateStatus(String id, String status) {
        jdbc.update(
                "UPDATE accounts SET status = ?, updated_at = ? WHERE id = ?",
                status, Instant.now().toString(), id);
    }

    public void updateStats(String id, int requestCount, int successCount, int errorCount,
                            int consecutiveErrors, long inputTokens, long outputTokens,
                            double credits, String cooldownUntil, String lastUsedAt) {
        jdbc.update("""
                        UPDATE accounts SET request_count = ?, success_count = ?, error_count = ?,
                            consecutive_errors = ?, input_tokens_total = ?, output_tokens_total = ?,
                            credits_total = ?, cooldown_until = ?, last_used_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                requestCount, successCount, errorCount, consecutiveErrors,
                inputTokens, outputTokens, credits, cooldownUntil, lastUsedAt,
                Instant.now().toString(), id);
    }

    private static final RowMapper<AccountRow> ACCOUNT_ROW_MAPPER = (rs, rowNum) -> new AccountRow(
            rs.getString("id"), rs.getString("name"), rs.getString("credentials"),
            rs.getString("auth_method"), rs.getString("status"),
            rs.getInt("request_count"), rs.getInt("success_count"), rs.getInt("error_count"),
            rs.getInt("consecutive_errors"),
            rs.getLong("input_tokens_total"), rs.getLong("output_tokens_total"),
            rs.getDouble("credits_total"),
            rs.getString("cooldown_until"), rs.getString("last_used_at"),
            rs.getString("created_at"), rs.getString("updated_at")
    );

    public record AccountRow(String id, String name, String credentials, String authMethod, String status,
                              int requestCount, int successCount, int errorCount, int consecutiveErrors,
                              long inputTokensTotal, long outputTokensTotal, double creditsTotal,
                              String cooldownUntil, String lastUsedAt,
                              String createdAt, String updatedAt) {}
}
