package com.kiro.gateway.pool;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kiro 账号实体
 */
public class Account {

    private final String id;
    private final String name;
    private final String credentials;
    private final String authMethod;

    private volatile String status;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicLong inputTokensTotal = new AtomicLong(0);
    private final AtomicLong outputTokensTotal = new AtomicLong(0);
    private volatile double creditsTotal = 0;
    private volatile Instant cooldownUntil;
    private volatile Instant lastUsedAt;
    private final Instant createdAt;

    public Account(String id, String name, String credentials, String authMethod) {
        this.id = id;
        this.name = name;
        this.credentials = credentials;
        this.authMethod = authMethod;
        this.status = "active";
        this.createdAt = Instant.now();
    }

    // 从数据库恢复
    public Account(String id, String name, String credentials, String authMethod,
                   String status, int requestCount, int successCount, int errorCount,
                   int consecutiveErrors, long inputTokensTotal, long outputTokensTotal,
                   double creditsTotal, String cooldownUntil, String lastUsedAt, String createdAt) {
        this.id = id;
        this.name = name;
        this.credentials = credentials;
        this.authMethod = authMethod;
        this.status = status;
        this.requestCount.set(requestCount);
        this.successCount.set(successCount);
        this.errorCount.set(errorCount);
        this.consecutiveErrors.set(consecutiveErrors);
        this.inputTokensTotal.set(inputTokensTotal);
        this.outputTokensTotal.set(outputTokensTotal);
        this.creditsTotal = creditsTotal;
        this.cooldownUntil = cooldownUntil != null ? Instant.parse(cooldownUntil) : null;
        this.lastUsedAt = lastUsedAt != null ? Instant.parse(lastUsedAt) : null;
        this.createdAt = createdAt != null ? Instant.parse(createdAt) : Instant.now();
    }

    /**
     * 是否可用（active 且不在冷却中）
     */
    public boolean isAvailable() {
        if (!"active".equals(status)) {
            return false;
        }
        if (cooldownUntil != null && Instant.now().isBefore(cooldownUntil)) {
            return false;
        }
        // 冷却已过期，自动恢复
        if (cooldownUntil != null && !Instant.now().isBefore(cooldownUntil)) {
            cooldownUntil = null;
            consecutiveErrors.set(0);
        }
        return true;
    }

    /**
     * 记录成功
     */
    public void recordSuccess(int inputTokens, int outputTokens, double credits) {
        requestCount.incrementAndGet();
        successCount.incrementAndGet();
        consecutiveErrors.set(0);
        inputTokensTotal.addAndGet(inputTokens);
        outputTokensTotal.addAndGet(outputTokens);
        creditsTotal += credits;
        lastUsedAt = Instant.now();
        cooldownUntil = null;
    }

    /**
     * 记录失败
     */
    public void recordError(boolean isRateLimit, int cooldownQuotaMinutes, int cooldownErrorMinutes, int errorThreshold) {
        requestCount.incrementAndGet();
        errorCount.incrementAndGet();
        int consecutive = consecutiveErrors.incrementAndGet();
        lastUsedAt = Instant.now();

        if (isRateLimit) {
            // 配额错误：长时间冷却
            cooldownUntil = Instant.now().plusSeconds(cooldownQuotaMinutes * 60L);
        } else if (consecutive >= errorThreshold) {
            // 连续错误超过阈值：短时间冷却
            cooldownUntil = Instant.now().plusSeconds(cooldownErrorMinutes * 60L);
        }
    }

    /**
     * 计算智能评分（0-100）
     * <p>
     * 成功率 60% + 新鲜度 20% + 负载均衡 20%
     */
    public double calculateScore() {
        int total = successCount.get() + errorCount.get();

        // 成功率（权重60%）
        double successRate = total == 0 ? 1.0 : (double) successCount.get() / total;
        double baseScore = successRate * 60;

        // 新鲜度（权重20%）
        double freshness;
        if (lastUsedAt == null) {
            freshness = 20;
        } else {
            double hoursSinceUse = (Instant.now().toEpochMilli() - lastUsedAt.toEpochMilli()) / 3600000.0;
            if (hoursSinceUse < 1) freshness = 20;
            else if (hoursSinceUse < 24) freshness = 15;
            else freshness = Math.max(5, 20 - hoursSinceUse / 24);
        }

        // 负载均衡（权重20%）
        double usageScore = Math.max(0, 20 - (total / 100.0));

        return baseScore + freshness + usageScore;
    }

    // --- getter ---

    public String id() { return id; }
    public String name() { return name; }
    public String credentials() { return credentials; }
    public String authMethod() { return authMethod; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int requestCount() { return requestCount.get(); }
    public int successCount() { return successCount.get(); }
    public int errorCount() { return errorCount.get(); }
    public int consecutiveErrors() { return consecutiveErrors.get(); }
    public long inputTokensTotal() { return inputTokensTotal.get(); }
    public long outputTokensTotal() { return outputTokensTotal.get(); }
    public double creditsTotal() { return creditsTotal; }
    public Instant cooldownUntil() { return cooldownUntil; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant createdAt() { return createdAt; }
}
