package com.kiro.gateway.trace;

import java.util.List;

/**
 * 完整追踪记录（用于持久化）
 */
public record TraceLog(
        String traceId,
        String apiType,
        String model,
        String accountId,
        long durationMs,
        boolean success,
        // ① 客户端请求
        String clientRequest,
        String clientHeaders,
        // ② Kiro 请求
        String kiroRequest,
        String kiroEndpoint,
        String kiroHeaders,
        // ③ Kiro 响应
        Integer kiroStatus,
        List<String> kiroEvents,
        int inputTokens,
        int outputTokens,
        double credits,
        // ④ 客户端响应
        String clientResponse,
        Integer clientStatus,
        String errorMessage,
        // 其他
        boolean stream,
        String apiKey
) {

    /**
     * Kiro 事件列表转 JSON 字符串
     */
    public String kiroEventsJson() {
        if (kiroEvents == null || kiroEvents.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < kiroEvents.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(kiroEvents.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
