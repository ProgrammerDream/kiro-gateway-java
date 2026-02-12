package com.kiro.gateway.model;

import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.dao.ModelDAO;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型解析器
 * <p>
 * 将外部模型名（如 gpt-4o, claude-3-5-sonnet）映射为 Kiro 内部模型 ID，
 * 并管理可用模型列表和缓存
 */
@Component
public class ModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelResolver.class);

    private final AppProperties properties;
    private final ModelDAO modelDAO;

    // 模型映射规则（按优先级排序）
    private final List<MappingRule> mappingRules = new ArrayList<>();
    // 可用模型缓存
    private final Map<String, ModelInfo> modelCache = new ConcurrentHashMap<>();
    // 已解析缓存
    private final Map<String, ResolveResult> resolveCache = new ConcurrentHashMap<>();

    public ModelResolver(AppProperties properties, ModelDAO modelDAO) {
        this.properties = properties;
        this.modelDAO = modelDAO;
    }

    @PostConstruct
    public void init() {
        loadModels();
        loadMappings();
        log.info("模型解析器初始化完成: {} 个模型, {} 条映射规则", modelCache.size(), mappingRules.size());
    }

    /**
     * 解析外部模型名 → Kiro 内部模型 ID
     *
     * @param externalModel 外部传入的模型名
     * @return 解析结果
     */
    public ResolveResult resolve(String externalModel) {
        if (externalModel == null || externalModel.isEmpty()) {
            return defaultResult();
        }

        // 先查缓存
        ResolveResult cached = resolveCache.get(externalModel.toLowerCase());
        if (cached != null) {
            return cached;
        }

        // 检查是否启用 thinking 模式
        boolean isThinking = false;
        String cleanModel = externalModel;
        String thinkingSuffix = properties.getThinking().getSuffix();
        if (cleanModel.endsWith(thinkingSuffix)) {
            isThinking = true;
            cleanModel = cleanModel.substring(0, cleanModel.length() - thinkingSuffix.length());
        }

        // 精确匹配
        ModelInfo exactMatch = modelCache.get(cleanModel);
        if (exactMatch != null && exactMatch.enabled) {
            ResolveResult result = new ResolveResult(exactMatch.id, cleanModel, isThinking, true);
            resolveCache.put(externalModel.toLowerCase(), result);
            return result;
        }

        // 映射规则匹配
        for (MappingRule rule : mappingRules) {
            if (!rule.enabled) continue;
            boolean matched = switch (rule.matchType) {
                case "exact" -> cleanModel.equalsIgnoreCase(rule.pattern);
                case "prefix" -> cleanModel.toLowerCase().startsWith(rule.pattern.toLowerCase());
                case "contains" -> cleanModel.toLowerCase().contains(rule.pattern.toLowerCase());
                case "regex" -> cleanModel.matches(rule.pattern);
                default -> false;
            };
            if (matched) {
                ResolveResult result = new ResolveResult(rule.internalId, externalModel, isThinking, true);
                resolveCache.put(externalModel.toLowerCase(), result);
                return result;
            }
        }

        // 未匹配，使用默认模型
        log.debug("模型 '{}' 未找到映射，使用默认模型", externalModel);
        return defaultResult();
    }

    /**
     * 获取所有可用模型列表（用于 /v1/models 端点）
     */
    public List<ModelInfo> listModels() {
        return modelCache.values().stream()
                .filter(m -> m.enabled)
                .sorted(Comparator.comparingInt(m -> m.displayOrder))
                .toList();
    }

    /**
     * 刷新模型缓存
     */
    public void refresh() {
        resolveCache.clear();
        loadModels();
        loadMappings();
        log.info("模型缓存已刷新: {} 个模型, {} 条映射规则", modelCache.size(), mappingRules.size());
    }

    private void loadModels() {
        modelCache.clear();
        for (ModelDAO.ModelInfo m : modelDAO.findAllModels()) {
            ModelInfo model = new ModelInfo(m.id(), m.displayName(), m.maxTokens(), m.ownedBy(), m.enabled(), m.displayOrder());
            modelCache.put(model.id, model);
        }
    }

    private void loadMappings() {
        mappingRules.clear();
        for (ModelDAO.MappingRule m : modelDAO.findEnabledMappings()) {
            mappingRules.add(new MappingRule(m.externalPattern(), m.internalId(), m.matchType(), m.priority(), m.enabled()));
        }
    }

    private ResolveResult defaultResult() {
        // 默认使用第一个可用模型
        String defaultId = modelCache.values().stream()
                .filter(m -> m.enabled)
                .min(Comparator.comparingInt(m -> m.displayOrder))
                .map(m -> m.id)
                .orElse("claude-sonnet-4-5-20250929");
        return new ResolveResult(defaultId, defaultId, false, false);
    }

    // ==================== 数据类 ====================

    public record ResolveResult(String kiroModelId, String requestedModel, boolean thinking, boolean matched) {}

    public static class ModelInfo {
        public final String id;
        public final String displayName;
        public final int maxTokens;
        public final String ownedBy;
        public final boolean enabled;
        public final int displayOrder;

        public ModelInfo(String id, String displayName, int maxTokens, String ownedBy, boolean enabled, int displayOrder) {
            this.id = id;
            this.displayName = displayName;
            this.maxTokens = maxTokens;
            this.ownedBy = ownedBy;
            this.enabled = enabled;
            this.displayOrder = displayOrder;
        }
    }

    private record MappingRule(String pattern, String internalId, String matchType, int priority, boolean enabled) {}
}
