package com.kiro.gateway.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型与映射规则 DAO
 */
@Component
public class ModelDAO {

    private final JdbcTemplate jdbc;

    public ModelDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelInfo> findAllModels() {
        return jdbc.query(
                "SELECT * FROM models ORDER BY display_order, id",
                (rs, rowNum) -> new ModelInfo(
                        rs.getString("id"),
                        rs.getString("display_name"),
                        rs.getInt("max_tokens"),
                        rs.getString("owned_by"),
                        rs.getInt("enabled") == 1,
                        rs.getInt("display_order")));
    }

    public List<MappingRule> findEnabledMappings() {
        return jdbc.query(
                "SELECT * FROM model_mappings WHERE enabled = 1 ORDER BY priority DESC",
                (rs, rowNum) -> new MappingRule(
                        rs.getString("external_pattern"),
                        rs.getString("internal_id"),
                        rs.getString("match_type"),
                        rs.getInt("priority"),
                        rs.getInt("enabled") == 1));
    }

    public record ModelInfo(String id, String displayName, int maxTokens,
                            String ownedBy, boolean enabled, int displayOrder) {}

    public record MappingRule(String externalPattern, String internalId,
                              String matchType, int priority, boolean enabled) {}
}
