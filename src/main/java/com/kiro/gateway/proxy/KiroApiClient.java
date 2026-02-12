package com.kiro.gateway.proxy;

import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.exception.KiroApiException;
import com.kiro.gateway.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

/**
 * Kiro API 客户端
 * <p>
 * 双端点回退：CodeWhisperer → AmazonQ（429时自动切换）
 * 每步操作写入 TraceContext
 */
@Component
public class KiroApiClient {

    private static final Logger log = LoggerFactory.getLogger(KiroApiClient.class);

    private final HttpClient httpClient;
    private final AppProperties properties;
    private final RetryHandler retryHandler;

    public KiroApiClient(HttpClient kiroHttpClient, AppProperties properties, RetryHandler retryHandler) {
        this.httpClient = kiroHttpClient;
        this.properties = properties;
        this.retryHandler = retryHandler;
    }

    /**
     * 调用 Kiro API（流式）
     * <p>
     * 支持双端点回退和自动重试
     *
     * @param payload     请求体 JSON
     * @param accessToken access token
     * @param traceCtx    追踪上下文
     * @param callback    流式回调
     */
    public void callStream(String payload, String accessToken, TraceContext traceCtx, StreamCallback callback) {
        List<String> endpoints = properties.getEndpoints();

        for (int epIdx = 0; epIdx < endpoints.size(); epIdx++) {
            String endpoint = endpoints.get(epIdx);

            for (int attempt = 0; attempt <= retryHandler.maxRetries(); attempt++) {
                try {
                    // 构建请求头
                    String headersJson = buildHeadersJson(accessToken, endpoint);

                    // 记录 Kiro 请求（阶段 ②）
                    traceCtx.recordKiroRequest(payload, endpoint, headersJson);

                    HttpRequest request = buildRequest(endpoint, payload, accessToken);
                    HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                    int statusCode = response.statusCode();
                    traceCtx.recordKiroResponseStatus(statusCode);

                    // 认证错误，不回退不重试
                    if (statusCode == 401 || statusCode == 403) {
                        String body = readBody(response);
                        throw new KiroApiException(statusCode, body);
                    }

                    // 429 尝试切换端点
                    if (statusCode == 429) {
                        if (epIdx < endpoints.size() - 1) {
                            log.warn("端点 {} 返回 429, 切换到下一个端点", endpoint);
                            break;
                        }
                        // 最后一个端点，尝试重试
                        if (retryHandler.shouldRetry(statusCode, attempt)) {
                            retryHandler.waitBeforeRetry(attempt, statusCode);
                            continue;
                        }
                        String body = readBody(response);
                        throw new KiroApiException(statusCode, body);
                    }

                    // 5xx 服务器错误，重试
                    if (statusCode >= 500) {
                        if (retryHandler.shouldRetry(statusCode, attempt)) {
                            retryHandler.waitBeforeRetry(attempt, statusCode);
                            continue;
                        }
                        String body = readBody(response);
                        throw new KiroApiException(statusCode, body);
                    }

                    // 其他非 200 错误
                    if (statusCode != 200) {
                        String body = readBody(response);
                        throw new KiroApiException(statusCode, body);
                    }

                    // 成功：解析 Event Stream
                    parseEventStream(response.body(), traceCtx, callback);
                    return;

                } catch (KiroApiException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("调用 Kiro API 异常: endpoint={}, attempt={}", endpoint, attempt, e);
                    if (retryHandler.shouldRetry(500, attempt)) {
                        retryHandler.waitBeforeRetry(attempt, 500);
                        continue;
                    }
                    throw new KiroApiException(500, e.getMessage(), e);
                }
            }
        }
    }

    private HttpRequest buildRequest(String endpoint, String payload, String accessToken) {
        String region = properties.getRegion();
        String kiroVersion = properties.getKiroVersion();
        String machineId = UUID.randomUUID().toString().replace("-", "");

        String xAmzUserAgent = "aws-sdk-js/1.0.27 KiroIDE-" + kiroVersion + "-" + machineId;
        String userAgent = "aws-sdk-js/1.0.27 ua/2.1 os/windows lang/java api/codewhispererstreaming#1.0.27 m/E KiroIDE-" + kiroVersion + "-" + machineId;

        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("x-amzn-codewhisperer-optout", "true")
                .header("x-amzn-kiro-agent-mode", "vibe")
                .header("x-amz-user-agent", xAmzUserAgent)
                .header("User-Agent", userAgent)
                .header("amz-sdk-invocation-id", UUID.randomUUID().toString())
                .header("amz-sdk-request", "attempt=1; max=3")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private String buildHeadersJson(String accessToken, String endpoint) {
        // 脱敏 token，只保留前8位
        String maskedToken = accessToken.length() > 8 ? accessToken.substring(0, 8) + "***" : "***";
        return "{\"Authorization\":\"Bearer " + maskedToken + "\",\"endpoint\":\"" + endpoint + "\"}";
    }

    private void parseEventStream(InputStream body, TraceContext traceCtx, StreamCallback callback) {
        EventStreamParser parser = new EventStreamParser(new StreamCallback() {
            @Override
            public void onText(String text) {
                traceCtx.appendKiroEvent("{\"type\":\"text\",\"content\":\"" + escapeJson(text) + "\"}");
                callback.onText(text);
            }

            @Override
            public void onThinking(String thinking) {
                traceCtx.appendKiroEvent("{\"type\":\"thinking\",\"content\":\"" + escapeJson(thinking) + "\"}");
                callback.onThinking(thinking);
            }

            @Override
            public void onToolUseStart(String toolUseId, String name) {
                traceCtx.appendKiroEvent("{\"type\":\"tool_use_start\",\"id\":\"" + toolUseId + "\",\"name\":\"" + name + "\"}");
                callback.onToolUseStart(toolUseId, name);
            }

            @Override
            public void onToolUseInput(String toolUseId, String inputDelta) {
                callback.onToolUseInput(toolUseId, inputDelta);
            }

            @Override
            public void onToolUseEnd(String toolUseId) {
                traceCtx.appendKiroEvent("{\"type\":\"tool_use_end\",\"id\":\"" + toolUseId + "\"}");
                callback.onToolUseEnd(toolUseId);
            }

            @Override
            public void onUsage(int inputTokens, int outputTokens) {
                traceCtx.recordTokenUsage(inputTokens, outputTokens, 0);
                callback.onUsage(inputTokens, outputTokens);
            }

            @Override
            public void onCredits(double credits) {
                traceCtx.appendKiroEvent("{\"type\":\"metering\",\"credits\":" + credits + "}");
                traceCtx.recordTokenUsage(traceCtx.inputTokens(), traceCtx.outputTokens(), credits);
                callback.onCredits(credits);
            }

            @Override
            public void onContextUsage(double percentage) {
                callback.onContextUsage(percentage);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(String error) {
                traceCtx.recordError(error);
                callback.onError(error);
            }
        });

        try {
            byte[] buf = new byte[8192];
            int len;
            while ((len = body.read(buf)) != -1) {
                byte[] chunk = new byte[len];
                System.arraycopy(buf, 0, chunk, 0, len);
                parser.feed(chunk);
            }
            parser.finish();
        } catch (Exception e) {
            log.error("解析 Event Stream 失败", e);
            callback.onError(e.getMessage());
        }
    }

    private String readBody(HttpResponse<InputStream> response) {
        try {
            return new String(response.body().readAllBytes());
        } catch (Exception e) {
            return "读取响应体失败: " + e.getMessage();
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
