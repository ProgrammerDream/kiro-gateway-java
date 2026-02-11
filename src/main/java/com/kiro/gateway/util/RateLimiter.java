package com.kiro.gateway.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简易滑动窗口限流器
 * <p>
 * 基于 IP 或 API Key 的请求频率限制
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * @param maxRequests 窗口内最大请求数
     * @param windowMs   窗口时间（毫秒）
     */
    public RateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    /**
     * 尝试获取许可
     *
     * @param key 限流键（IP / API Key）
     * @return true 允许通过，false 被限流
     */
    public boolean tryAcquire(String key) {
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());
        long now = System.currentTimeMillis();

        // 窗口过期，重置
        if (now - counter.windowStart.get() > windowMs) {
            counter.windowStart.set(now);
            counter.count.set(1);
            return true;
        }

        return counter.count.incrementAndGet() <= maxRequests;
    }

    /**
     * 获取剩余配额
     */
    public int remaining(String key) {
        WindowCounter counter = counters.get(key);
        if (counter == null) return maxRequests;

        long now = System.currentTimeMillis();
        if (now - counter.windowStart.get() > windowMs) {
            return maxRequests;
        }
        return Math.max(0, maxRequests - counter.count.get());
    }

    /**
     * 清理过期计数器（由定时任务调用）
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> now - e.getValue().windowStart.get() > windowMs * 2);
    }

    private static class WindowCounter {
        final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        final AtomicInteger count = new AtomicInteger(0);
    }
}
