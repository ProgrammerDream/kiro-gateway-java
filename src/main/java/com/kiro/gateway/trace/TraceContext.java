package com.kiro.gateway.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单次请求追踪上下文
 * <p>
 * 贯穿整个请求链路，收集四个阶段的数据：
 * ① 客户端请求 → ② Kiro 请求 → ③ Kiro 响应 → ④ 客户端响应
 */
public class TraceContext {

    private final String traceId;
    private final Instant startTime;

    // ① 客户端请求
    private String clientRequest;
    private String clientHeaders;
    private String apiType;
    private String model;

    // ② Kiro 请求
    private String kiroRequest;
    private String kiroEndpoint;
    private String kiroHeaders;

    // ③ Kiro 响应
    private Integer kiroStatus;
    private final List<String> kiroEvents = new ArrayList<>();
    private int inputTokens;
    private int outputTokens;
    private double credits;

    // ④ 客户端响应
    private String clientResponse;
    private Integer clientStatus;

    // 通用
    private String accountId;
    private boolean success = true;
    private String errorMessage;
    private boolean stream;
    private String apiKey;

    private TraceContext(String traceId) {
        this.traceId = traceId;
        this.startTime = Instant.now();
    }

    /**
     * 创建新的追踪上下文
     */
    public static TraceContext create() {
        return new TraceContext(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    /**
     * 使用指定 traceId 创建
     */
    public static TraceContext create(String traceId) {
        return new TraceContext(traceId);
    }

    /**
     * 记录客户端请求（阶段 ①）
     */
    public void recordClientRequest(String requestBody, String headers, String apiType, String model) {
        this.clientRequest = requestBody;
        this.clientHeaders = headers;
        this.apiType = apiType;
        this.model = model;
    }

    /**
     * 记录 Kiro 请求（阶段 ②）
     */
    public void recordKiroRequest(String requestBody, String endpoint, String headers) {
        this.kiroRequest = requestBody;
        this.kiroEndpoint = endpoint;
        this.kiroHeaders = headers;
    }

    /**
     * 记录 Kiro 响应状态（阶段 ③）
     */
    public void recordKiroResponseStatus(int status) {
        this.kiroStatus = status;
    }

    /**
     * 追加 Kiro 事件
     */
    public void appendKiroEvent(String event) {
        kiroEvents.add(event);
    }

    /**
     * 记录 Token 使用量
     */
    public void recordTokenUsage(int inputTokens, int outputTokens, double credits) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.credits = credits;
    }

    /**
     * 记录 credits 消耗
     */
    public void recordCredits(double credits) {
        this.credits = credits;
    }

    /**
     * 记录客户端响应（阶段 ④）
     */
    public void recordClientResponse(String responseBody, int status) {
        this.clientResponse = responseBody;
        this.clientStatus = status;
    }

    /**
     * 记录错误
     */
    public void recordError(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }

    /**
     * 计算耗时（毫秒）
     */
    public long durationMs() {
        return Instant.now().toEpochMilli() - startTime.toEpochMilli();
    }

    /**
     * 转换为持久化记录
     */
    public TraceLog toTraceLog() {
        return new TraceLog(
                traceId, apiType, model, accountId, durationMs(), success,
                clientRequest, clientHeaders,
                kiroRequest, kiroEndpoint, kiroHeaders,
                kiroStatus, kiroEvents, inputTokens, outputTokens, credits,
                clientResponse, clientStatus, errorMessage,
                stream, apiKey
        );
    }

    // --- getter / setter ---

    public String traceId() { return traceId; }
    public Instant startTime() { return startTime; }
    public String apiType() { return apiType; }
    public String model() { return model; }
    public String accountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public boolean success() { return success; }
    public int inputTokens() { return inputTokens; }
    public int outputTokens() { return outputTokens; }
    public double credits() { return credits; }
    public boolean stream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    public String apiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String errorMessage() { return errorMessage; }
    public String kiroEndpoint() { return kiroEndpoint; }
}
