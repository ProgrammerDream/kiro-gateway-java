package com.kiro.gateway.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * API Key DAO
 */
@Component
public class ApiKeyDAO {

    private final JdbcTemplate jdbc;

    public ApiKeyDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ApiKeyRow> findAll() {
        return jdbc.query(
                "SELECT * FROM api_keys ORDER BY created_at",
                (rs, rowNum) -> new ApiKeyRow(
                        rs.getString("key"), rs.getString("name"), rs.getString("created_at")));
    }

    public boolean validate(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE key = ?", Integer.class, key);
        return count != null && count > 0;
    }

    public record ApiKeyRow(String key, String name, String createdAt) {}
}
