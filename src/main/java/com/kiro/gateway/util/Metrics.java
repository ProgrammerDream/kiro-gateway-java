package com.kiro.gateway.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus 风格指标收集器
 * <p>
 * 请求计数、延迟直方图、Token 使用统计
 */
public class Metrics {

    private static final Metrics INSTANCE = new Metrics();

    // 计数器
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    // 延迟直方图桶
    private final long[] bucketBounds = {50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000};
    private final ConcurrentHashMap<String, long[]> histograms = new ConcurrentHashMap<>();

    public static Metrics instance() {
        return INSTANCE;
    }

    /**
     * 递增计数器
     */
    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 增加计数器指定值
     */
    public void add(String name, long value) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(value);
    }

    /**
     * 获取计数器值
     */
    public long get(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 记录延迟到直方图
     */
    public void recordLatency(String name, long latencyMs) {
        long[] buckets = histograms.computeIfAbsent(name, k -> new long[bucketBounds.length + 1]);
        synchronized (buckets) {
            for (int i = 0; i < bucketBounds.length; i++) {
                if (latencyMs <= bucketBounds[i]) {
                    buckets[i]++;
                    return;
                }
            }
            buckets[bucketBounds.length]++;
        }
    }

    /**
     * 记录请求
     */
    public void recordRequest(String apiType, String model, boolean success, long latencyMs,
                               int inputTokens, int outputTokens) {
        increment("requests_total");
        increment("requests_" + apiType);
        increment(success ? "requests_success" : "requests_error");
        add("tokens_input_total", inputTokens);
        add("tokens_output_total", outputTokens);
        recordLatency("request_latency", latencyMs);
    }

    /**
     * 输出 Prometheus 文本格式
     */
    public String toPrometheusFormat() {
        StringBuilder sb = new StringBuilder();

        // 计数器
        counters.forEach((name, value) -> {
            sb.append("# TYPE kiro_").append(name).append(" counter\n");
            sb.append("kiro_").append(name).append(" ").append(value.get()).append("\n");
        });

        // 直方图
        histograms.forEach((name, buckets) -> {
            sb.append("# TYPE kiro_").append(name).append(" histogram\n");
            long cumulative = 0;
            synchronized (buckets) {
                for (int i = 0; i < bucketBounds.length; i++) {
                    cumulative += buckets[i];
                    sb.append("kiro_").append(name).append("_bucket{le=\"")
                            .append(bucketBounds[i]).append("\"} ").append(cumulative).append("\n");
                }
                cumulative += buckets[bucketBounds.length];
                sb.append("kiro_").append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append("\n");
                sb.append("kiro_").append(name).append("_count ").append(cumulative).append("\n");
            }
        });

        return sb.toString();
    }
}
