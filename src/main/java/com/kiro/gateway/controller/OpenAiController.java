package com.kiro.gateway.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.auth.AuthService;
import com.kiro.gateway.model.ModelResolver;
import com.kiro.gateway.pool.Account;
import com.kiro.gateway.pool.AccountPool;
import com.kiro.gateway.proxy.KiroApiClient;
import com.kiro.gateway.proxy.StreamCallback;
import com.kiro.gateway.trace.TraceContext;
import com.kiro.gateway.trace.TraceFilter;
import com.kiro.gateway.trace.TraceStore;
import com.kiro.gateway.translator.OpenAiTranslator;
import com.kiro.gateway.translator.RequestTranslator;
import com.kiro.gateway.translator.ThinkingParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 兼容 API 端点
 * <p>
 * POST /v1/chat/completions — 流式 + 非流式
 * GET  /v1/models            — 模型列表
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final AccountPool accountPool;
    private final AuthService authService;
    private final KiroApiClient kiroClient;
    private final OpenAiTranslator translator;
    private final ModelResolver modelResolver;
    private final TraceStore traceStore;

    public OpenAiController(AccountPool accountPool, AuthService authService,
                            KiroApiClient kiroClient, OpenAiTranslator translator,
                            ModelResolver modelResolver, TraceStore traceStore) {
        this.accountPool = accountPool;
        this.authService = authService;
        this.kiroClient = kiroClient;
        this.translator = translator;
        this.modelResolver = modelResolver;
        this.traceStore = traceStore;
    }

    /**
     * POST /v1/chat/completions
     */
    @PostMapping(value = "/chat/completions")
    public Mono<Void> chatCompletions(@RequestBody String body, ServerWebExchange exchange) {
        JSONObject request = JSONObject.parseObject(body);
        boolean stream = request.getBooleanValue("stream", false);

        TraceContext traceCtxRaw = TraceFilter.getTraceContext(exchange);
        final TraceContext traceCtx = traceCtxRaw != null ? traceCtxRaw : TraceContext.create();

        // 解析模型
        String requestedModel = request.getString("model");
        ModelResolver.ResolveResult resolved = modelResolver.resolve(requestedModel);

        // 记录客户端请求（阶段 ①）
        String headers = extractRequestHeaders(exchange);
        traceCtx.recordClientRequest(body, headers, "openai", requestedModel);
        traceCtx.setStream(stream);

        // 获取账号
        Account account = accountPool.getNext();
        traceCtx.setAccountId(account.id());

        // 获取 access token
        String accessToken = authService.getAccessToken(account.id(), account.credentials(), account.authMethod());

        // 转换请求
        RequestTranslator.TranslateResult translated = translator.translate(request, resolved.kiroModelId(), resolved.thinking());
        String payload = translated.payload().toJsonString();

        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();

        if (stream) {
            exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            exchange.getResponse().getHeaders().setCacheControl("no-cache");
            Flux<String> sseFlux = streamResponse(payload, accessToken, traceCtx, account, resolved, translated.toolNameMap());
            return exchange.getResponse().writeAndFlushWith(
                    sseFlux.map(s -> Mono.just(bufferFactory.wrap(s.getBytes(StandardCharsets.UTF_8))))
            );
        }

        // 非流式：直接写 JSON 字节，避免 Jackson 二次序列化
        return Mono.fromCallable(() -> {
            NonStreamResult result = callNonStream(payload, accessToken, traceCtx, account, resolved, translated.toolNameMap());
            return result.response.toJSONString();
        }).flatMap(json -> {
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponse().getHeaders().setContentLength(bytes.length);
            DataBuffer buffer = bufferFactory.wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        });
    }

    /**
     * GET /v1/models
     */
    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> listModels() {
        List<ModelResolver.ModelInfo> models = modelResolver.listModels();
        JSONArray data = new JSONArray();
        for (ModelResolver.ModelInfo model : models) {
            JSONObject item = JSONObject.of(
                    "id", model.id, //
                    "object", "model", //
                    "created", System.currentTimeMillis() / 1000, //
                    "owned_by", model.ownedBy //
            );
            data.add(item);
        }
        JSONObject response = JSONObject.of("object", "list", "data", data);
        return Mono.just(response.toJSONString());
    }

    // ==================== 流式响应 ====================

    private Flux<String> streamResponse(String payload, String accessToken,
                                         TraceContext traceCtx, Account account,
                                         ModelResolver.ResolveResult resolved,
                                         Map<String, String> toolNameMap) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, String> reverseToolMap = reverseMap(toolNameMap);
        ThinkingParser thinkingParser = resolved.thinking() ? new ThinkingParser() : null;

        // 整个流共享同一个 id 和 created
        final String completionId = translator.generateCompletionId();
        final long created = System.currentTimeMillis() / 1000;

        // 工具调用状态
        final int[] toolIndex = {0};
        final Map<String, String> toolCallIds = new LinkedHashMap<>();
        // Token 估算跟踪（thinking + 正文都计入 output）
        final int[] outputLength = {0};
        final double[] contextUsagePct = {-1};
        ModelResolver.ModelInfo modelInfo = modelResolver.getModelInfo(resolved.kiroModelId());
        final int maxTokens = modelInfo != null ? modelInfo.maxTokens : 200000;

        new Thread(() -> {
            try {
                kiroClient.callStream(payload, accessToken, traceCtx, new StreamCallback() {
                    @Override
                    public void onText(String text) {
                        outputLength[0] += text.length();
                        if (thinkingParser == null) {
                            emitChunk(sink, completionId, created, resolved.requestedModel(), text, null, null, null, 0, 0);
                            return;
                        }
                        ThinkingParser.ParseResult parsed = thinkingParser.feed(text);
                        if (parsed.hasThinking()) {
                            emitChunk(sink, completionId, created, resolved.requestedModel(), null, parsed.thinkingDelta(), null, null, 0, 0);
                        }
                        if (parsed.hasContent()) {
                            emitChunk(sink, completionId, created, resolved.requestedModel(), parsed.contentDelta(), null, null, null, 0, 0);
                        }
                    }

                    @Override
                    public void onThinking(String thinking) {
                        outputLength[0] += thinking.length();
                        emitChunk(sink, completionId, created, resolved.requestedModel(), null, thinking, null, null, 0, 0);
                    }

                    @Override
                    public void onToolUseStart(String toolUseId, String name) {
                        String originalName = reverseToolMap.getOrDefault(name, name);
                        String callId = "call_" + toolUseId;
                        toolCallIds.put(toolUseId, callId);

                        JSONObject delta = new JSONObject();
                        delta.put("index", toolIndex[0]);
                        delta.put("id", callId);
                        delta.put("type", "function");
                        delta.put("function", JSONObject.of("name", originalName, "arguments", ""));
                        emitChunk(sink, completionId, created, resolved.requestedModel(), null, null, delta, null, 0, 0);
                    }

                    @Override
                    public void onToolUseInput(String toolUseId, String inputDelta) {
                        JSONObject delta = new JSONObject();
                        delta.put("index", toolIndex[0]);
                        delta.put("function", JSONObject.of("arguments", inputDelta));
                        emitChunk(sink, completionId, created, resolved.requestedModel(), null, null, delta, null, 0, 0);
                    }

                    @Override
                    public void onToolUseEnd(String toolUseId) {
                        toolIndex[0]++;
                    }

                    @Override
                    public void onUsage(int inputTokens, int outputTokens) {}

                    @Override
                    public void onCredits(double credits) {}

                    @Override
                    public void onContextUsage(double percentage) {
                        contextUsagePct[0] = percentage;
                    }

                    @Override
                    public void onComplete() {
                        if (thinkingParser != null) {
                            ThinkingParser.ParseResult last = thinkingParser.finish();
                            if (last.hasThinking()) {
                                emitChunk(sink, completionId, created, resolved.requestedModel(), null, last.thinkingDelta(), null, null, 0, 0);
                            }
                            if (last.hasContent()) {
                                emitChunk(sink, completionId, created, resolved.requestedModel(), last.contentDelta(), null, null, null, 0, 0);
                            }
                        }
                        // 从 contextUsagePercentage 推算 token
                        if (traceCtx.inputTokens() == 0 && contextUsagePct[0] > 0) {
                            int outputTokens = Math.max(1, outputLength[0] / 4);
                            int totalTokens = (int) (contextUsagePct[0] / 100.0 * maxTokens);
                            int inputTokens = Math.max(0, totalTokens - outputTokens);
                            traceCtx.recordTokenUsage(inputTokens, outputTokens, traceCtx.credits());
                        }

                        String finishReason = toolCallIds.isEmpty() ? "stop" : "tool_calls";
                        emitChunk(sink, completionId, created, resolved.requestedModel(), null, null, null, finishReason,
                                traceCtx.inputTokens(), traceCtx.outputTokens());
                        sink.tryEmitNext("data: [DONE]\n\n");
                        sink.tryEmitComplete();

                        // 记录成功
                        accountPool.recordSuccess(account.id(), traceCtx.inputTokens(), traceCtx.outputTokens(), traceCtx.credits());
                        traceCtx.recordClientResponse("[streaming]", 200);
                        traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());
                    }

                    @Override
                    public void onError(String error) {
                        traceCtx.recordError(error);
                        accountPool.recordError(account.id(), false);
                        traceCtx.recordClientResponse("{\"error\":\"" + error + "\"}", 500);
                        traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());
                        sink.tryEmitComplete();
                    }
                });
            } catch (Exception e) {
                log.error("流式请求异常", e);
                traceCtx.recordError(e.getMessage());
                accountPool.recordError(account.id(), e.getMessage() != null && e.getMessage().contains("429"));
                traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());
                sink.tryEmitComplete();
            }
        }).start();

        return sink.asFlux();
    }

    private void emitChunk(Sinks.Many<String> sink, String completionId, long created,
                            String model, String content, String reasoningContent,
                            JSONObject toolCallDelta, String finishReason,
                            int inputTokens, int outputTokens) {
        JSONObject chunk = translator.toOpenAiStreamChunk(completionId, created, model, content,
                reasoningContent, toolCallDelta, finishReason, inputTokens, outputTokens);
        sink.tryEmitNext("data: " + chunk.toJSONString() + "\n\n");
    }

    // ==================== 非流式响应 ====================

    private NonStreamResult callNonStream(String payload, String accessToken,
                                           TraceContext traceCtx, Account account,
                                           ModelResolver.ResolveResult resolved,
                                           Map<String, String> toolNameMap) {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        Map<String, String> reverseToolMap = reverseMap(toolNameMap);
        JSONArray toolCalls = new JSONArray();
        ThinkingParser thinkingParser = resolved.thinking() ? new ThinkingParser() : null;
        final double[] contextUsagePct = {-1};
        ModelResolver.ModelInfo modelInfo = modelResolver.getModelInfo(resolved.kiroModelId());
        final int maxTokensVal = modelInfo != null ? modelInfo.maxTokens : 200000;

        final int[] toolIndex = {0};
        Map<String, JSONObject> toolCallBuffers = new LinkedHashMap<>();

        try {
            kiroClient.callStream(payload, accessToken, traceCtx, new StreamCallback() {
                @Override
                public void onText(String text) {
                    if (thinkingParser == null) {
                        contentBuilder.append(text);
                        return;
                    }
                    ThinkingParser.ParseResult parsed = thinkingParser.feed(text);
                    if (parsed.hasThinking()) {
                        thinkingBuilder.append(parsed.thinkingDelta());
                    }
                    if (parsed.hasContent()) {
                        contentBuilder.append(parsed.contentDelta());
                    }
                }

                @Override
                public void onThinking(String thinking) {
                    thinkingBuilder.append(thinking);
                }

                @Override
                public void onToolUseStart(String toolUseId, String name) {
                    String originalName = reverseToolMap.getOrDefault(name, name);
                    JSONObject tc = new JSONObject();
                    tc.put("id", "call_" + toolUseId);
                    tc.put("type", "function");
                    tc.put("function", JSONObject.of("name", originalName, "arguments", ""));
                    toolCallBuffers.put(toolUseId, tc);
                }

                @Override
                public void onToolUseInput(String toolUseId, String inputDelta) {
                    JSONObject tc = toolCallBuffers.get(toolUseId);
                    if (tc != null) {
                        JSONObject fn = tc.getJSONObject("function");
                        fn.put("arguments", fn.getString("arguments") + inputDelta);
                    }
                }

                @Override
                public void onToolUseEnd(String toolUseId) {
                    JSONObject tc = toolCallBuffers.get(toolUseId);
                    if (tc != null) {
                        toolCalls.add(tc);
                    }
                    toolIndex[0]++;
                }

                @Override
                public void onUsage(int inputTokens, int outputTokens) {}

                @Override
                public void onCredits(double credits) {}

                @Override
                public void onContextUsage(double percentage) {
                    contextUsagePct[0] = percentage;
                }

                @Override
                public void onComplete() {
                    if (thinkingParser != null) {
                        ThinkingParser.ParseResult last = thinkingParser.finish();
                        if (last.hasThinking()) {
                            thinkingBuilder.append(last.thinkingDelta());
                        }
                        if (last.hasContent()) {
                            contentBuilder.append(last.contentDelta());
                        }
                    }
                }

                @Override
                public void onError(String error) {
                    traceCtx.recordError(error);
                }
            });

            // thinking + 正文都计入 output
            if (traceCtx.inputTokens() == 0 && contextUsagePct[0] > 0) {
                int totalOutputLen = contentBuilder.length() + thinkingBuilder.length();
                int outputTokens = Math.max(1, totalOutputLen / 4);
                int totalTokens = (int) (contextUsagePct[0] / 100.0 * maxTokensVal);
                int inputTokens = Math.max(0, totalTokens - outputTokens);
                traceCtx.recordTokenUsage(inputTokens, outputTokens, traceCtx.credits());
            }

            // 记录成功
            String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
            String reasoningContent = thinkingBuilder.isEmpty() ? null : thinkingBuilder.toString();
            JSONObject response = translator.toOpenAiResponse(
                    contentBuilder.toString(), reasoningContent,
                    toolCalls.isEmpty() ? null : toolCalls,
                    traceCtx.inputTokens(), traceCtx.outputTokens(),
                    resolved.requestedModel(), finishReason
            );

            accountPool.recordSuccess(account.id(), traceCtx.inputTokens(), traceCtx.outputTokens(), traceCtx.credits());
            traceCtx.recordClientResponse(response.toJSONString(), 200);
            traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());

            return new NonStreamResult(response);
        } catch (Exception e) {
            accountPool.recordError(account.id(), e.getMessage() != null && e.getMessage().contains("429"));
            traceCtx.recordError(e.getMessage());
            traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());
            throw e;
        }
    }

    private String extractRequestHeaders(ServerWebExchange exchange) {
        JSONObject headers = new JSONObject();
        exchange.getRequest().getHeaders().forEach((k, v) -> {
            if (!"authorization".equalsIgnoreCase(k)) {
                headers.put(k, v.size() == 1 ? v.get(0) : v);
            }
        });
        return headers.toJSONString();
    }

    private Map<String, String> reverseMap(Map<String, String> map) {
        Map<String, String> reversed = new HashMap<>();
        map.forEach((k, v) -> reversed.put(v, k));
        return reversed;
    }

    private record NonStreamResult(JSONObject response) {}
}
