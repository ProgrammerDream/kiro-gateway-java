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
import com.kiro.gateway.translator.ClaudeTranslator;
import com.kiro.gateway.translator.RequestTranslator;
import com.kiro.gateway.translator.ThinkingParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.MediaType;
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
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Claude 兼容 API 端点
 * <p>
 * POST /v1/messages — 流式 + 非流式
 */
@RestController
@RequestMapping("/v1")
public class ClaudeController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeController.class);

    private final AccountPool accountPool;
    private final AuthService authService;
    private final KiroApiClient kiroClient;
    private final ClaudeTranslator translator;
    private final ModelResolver modelResolver;
    private final TraceStore traceStore;

    public ClaudeController(AccountPool accountPool, AuthService authService,
                            KiroApiClient kiroClient, ClaudeTranslator translator,
                            ModelResolver modelResolver, TraceStore traceStore) {
        this.accountPool = accountPool;
        this.authService = authService;
        this.kiroClient = kiroClient;
        this.translator = translator;
        this.modelResolver = modelResolver;
        this.traceStore = traceStore;
    }

    /**
     * POST /v1/messages
     */
    @PostMapping(value = "/messages")
    public Mono<Void> messages(@RequestBody String body, ServerWebExchange exchange) {
        JSONObject request = JSONObject.parseObject(body);
        boolean stream = request.getBooleanValue("stream", false);

        TraceContext traceCtxRaw = TraceFilter.getTraceContext(exchange);
        final TraceContext traceCtx = traceCtxRaw != null ? traceCtxRaw : TraceContext.create();

        // 解析模型
        String requestedModel = request.getString("model");
        ModelResolver.ResolveResult resolved = modelResolver.resolve(requestedModel);

        // 记录客户端请求（阶段 ①）
        String headers = extractRequestHeaders(exchange);
        traceCtx.recordClientRequest(body, headers, "claude", requestedModel);
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

    // ==================== 流式响应 ====================

    private Flux<String> streamResponse(String payload, String accessToken,
                                         TraceContext traceCtx, Account account,
                                         ModelResolver.ResolveResult resolved,
                                         Map<String, String> toolNameMap) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, String> reverseToolMap = reverseMap(toolNameMap);
        boolean thinkingEnabled = resolved.thinking();
        ThinkingParser thinkingParser = thinkingEnabled ? new ThinkingParser() : null;
        String thinkingSignature = "sig_" + UUID.randomUUID().toString().replace("-", "");

        // 发送 message_start
        emitEvent(sink, "message_start", JSONObject.of("type", "message_start", //
                "message", JSONObject.of("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), //
                        "type", "message", //
                        "role", "assistant", //
                        "model", resolved.requestedModel() //
                )));

        // 内容块索引和状态跟踪
        final int[] blockIndex = {0};
        final boolean[] thinkingBlockStarted = {false};
        final boolean[] textBlockStarted = {false};
        final boolean[] hasToolUse = {false};
        // Token 估算跟踪（thinking + 正文都计入 output）
        final int[] outputLength = {0};
        final double[] contextUsagePct = {-1};
        // 模型最大 token 数
        ModelResolver.ModelInfo modelInfo = modelResolver.getModelInfo(resolved.kiroModelId());
        final int maxTokens = modelInfo != null ? modelInfo.maxTokens : 200000;

        new Thread(() -> {
            try {
                kiroClient.callStream(payload, accessToken, traceCtx, new StreamCallback() {

                    /**
                     * 确保 thinking 块已关闭
                     */
                    private void closeThinkingBlockIfOpen() {
                        if (thinkingBlockStarted[0]) {
                            emitEvent(sink, "content_block_stop", JSONObject.of("type", "content_block_stop", //
                                    "index", blockIndex[0]));
                            thinkingBlockStarted[0] = false;
                            blockIndex[0]++;
                        }
                    }

                    /**
                     * 确保 text 块已开启
                     */
                    private void ensureTextBlockStarted() {
                        if (!textBlockStarted[0]) {
                            closeThinkingBlockIfOpen();
                            emitEvent(sink, "content_block_start", JSONObject.of("type", "content_block_start", //
                                    "index", blockIndex[0], //
                                    "content_block", JSONObject.of("type", "text", "text", "")));
                            textBlockStarted[0] = true;
                        }
                    }

                    /**
                     * 关闭 text 块（如果已开启）
                     */
                    private void closeTextBlockIfOpen() {
                        if (textBlockStarted[0]) {
                            emitEvent(sink, "content_block_stop", JSONObject.of("type", "content_block_stop", //
                                    "index", blockIndex[0]));
                            textBlockStarted[0] = false;
                            blockIndex[0]++;
                        }
                    }

                    @Override
                    public void onText(String text) {
                        outputLength[0] += text.length();
                        if (thinkingParser == null) {
                            ensureTextBlockStarted();
                            emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                    "index", blockIndex[0], //
                                    "delta", JSONObject.of("type", "text_delta", "text", text)));
                            return;
                        }
                        ThinkingParser.ParseResult parsed = thinkingParser.feed(text);
                        // ThinkingParser 从 <thinking> 标签中提取的 thinking 内容
                        if (parsed.hasThinking()) {
                            onThinking(parsed.thinkingDelta());
                        }
                        if (parsed.hasContent()) {
                            ensureTextBlockStarted();
                            emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                    "index", blockIndex[0], //
                                    "delta", JSONObject.of("type", "text_delta", "text", parsed.contentDelta())));
                        }
                    }

                    @Override
                    public void onThinking(String thinking) {
                        outputLength[0] += thinking.length();
                        // 防御性关闭 text 块（避免块嵌套）
                        closeTextBlockIfOpen();
                        // 开启 thinking 内容块（如果尚未开启）
                        if (!thinkingBlockStarted[0]) {
                            JSONObject thinkingBlock = new JSONObject();
                            thinkingBlock.put("type", "thinking");
                            thinkingBlock.put("thinking", "");
                            thinkingBlock.put("signature", thinkingSignature);
                            emitEvent(sink, "content_block_start", JSONObject.of("type", "content_block_start", //
                                    "index", blockIndex[0], //
                                    "content_block", thinkingBlock));
                            thinkingBlockStarted[0] = true;
                        }
                        // 发送 thinking delta
                        emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                "index", blockIndex[0], //
                                "delta", JSONObject.of("type", "thinking_delta", "thinking", thinking)));
                    }

                    @Override
                    public void onToolUseStart(String toolUseId, String name) {
                        hasToolUse[0] = true;
                        String originalName = reverseToolMap.getOrDefault(name, name);

                        // 关闭已有的 thinking/text 块
                        closeThinkingBlockIfOpen();
                        closeTextBlockIfOpen();

                        // 开始 tool_use block
                        JSONObject toolBlock = new JSONObject();
                        toolBlock.put("type", "tool_use");
                        toolBlock.put("id", "toolu_" + toolUseId);
                        toolBlock.put("name", originalName);
                        toolBlock.put("input", new JSONObject());
                        emitEvent(sink, "content_block_start", JSONObject.of("type", "content_block_start", //
                                "index", blockIndex[0], //
                                "content_block", toolBlock));
                    }

                    @Override
                    public void onToolUseInput(String toolUseId, String inputDelta) {
                        emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                "index", blockIndex[0], //
                                "delta", JSONObject.of("type", "input_json_delta", "partial_json", inputDelta)));
                    }

                    @Override
                    public void onToolUseEnd(String toolUseId) {
                        emitEvent(sink, "content_block_stop", JSONObject.of("type", "content_block_stop", //
                                "index", blockIndex[0]));
                        blockIndex[0]++;
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
                                onThinking(last.thinkingDelta());
                            }
                            if (last.hasContent()) {
                                ensureTextBlockStarted();
                                emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                        "index", blockIndex[0], //
                                        "delta", JSONObject.of("type", "text_delta", "text", last.contentDelta())));
                            }
                        }

                        // 关闭所有未关闭的块
                        closeThinkingBlockIfOpen();
                        closeTextBlockIfOpen();

                        // 从 contextUsagePercentage 推算 token
                        if (traceCtx.inputTokens() == 0 && contextUsagePct[0] > 0) {
                            int outputTokens = Math.max(1, outputLength[0] / 4);
                            int totalTokens = (int) (contextUsagePct[0] / 100.0 * maxTokens);
                            int inputTokens = Math.max(0, totalTokens - outputTokens);
                            traceCtx.recordTokenUsage(inputTokens, outputTokens, traceCtx.credits());
                        }

                        // message_delta
                        String stopReason = hasToolUse[0] ? "tool_use" : "end_turn";
                        JSONObject msgDelta = new JSONObject();
                        msgDelta.put("type", "message_delta");
                        msgDelta.put("delta", JSONObject.of("stop_reason", stopReason));
                        msgDelta.put("usage", JSONObject.of( //
                                "input_tokens", traceCtx.inputTokens(), //
                                "output_tokens", traceCtx.outputTokens() //
                        ));
                        emitEvent(sink, "message_delta", msgDelta);

                        // message_stop
                        emitEvent(sink, "message_stop", JSONObject.of("type", "message_stop"));
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
                        traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());
                        sink.tryEmitComplete();
                    }
                });
            } catch (Exception e) {
                log.error("Claude 流式请求异常", e);
                traceCtx.recordError(e.getMessage());
                accountPool.recordError(account.id(), e.getMessage() != null && e.getMessage().contains("429"));
                traceStore.saveWithRequestLog(traceCtx.toTraceLog(), account.name());
                sink.tryEmitComplete();
            }
        }).start();

        return sink.asFlux();
    }

    private void emitEvent(Sinks.Many<String> sink, String eventType, JSONObject data) {
        sink.tryEmitNext("event: " + eventType + "\ndata: " + data.toJSONString() + "\n\n");
    }

    // ==================== 非流式响应 ====================

    private NonStreamResult callNonStream(String payload, String accessToken,
                                           TraceContext traceCtx, Account account,
                                           ModelResolver.ResolveResult resolved,
                                           Map<String, String> toolNameMap) {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder thinkingBuilder = new StringBuilder();
        Map<String, String> reverseToolMap = reverseMap(toolNameMap);
        JSONArray toolUses = new JSONArray();
        ThinkingParser thinkingParser = resolved.thinking() ? new ThinkingParser() : null;
        final double[] contextUsagePct = {-1};
        ModelResolver.ModelInfo modelInfo = modelResolver.getModelInfo(resolved.kiroModelId());
        final int maxTokensVal = modelInfo != null ? modelInfo.maxTokens : 200000;

        Map<String, JSONObject> toolUseBuffers = new LinkedHashMap<>();

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
                    JSONObject tu = new JSONObject();
                    tu.put("type", "tool_use");
                    tu.put("id", "toolu_" + toolUseId);
                    tu.put("name", originalName);
                    tu.put("input", new JSONObject());
                    toolUseBuffers.put(toolUseId, tu);
                }

                @Override
                public void onToolUseInput(String toolUseId, String inputDelta) {
                    // 暂存，完成时组装
                    JSONObject tu = toolUseBuffers.get(toolUseId);
                    if (tu != null) {
                        String current = tu.getJSONObject("input").toJSONString();
                        // 简化处理：累积 JSON 片段
                        tu.put("_inputRaw", tu.getString("_inputRaw") == null ? inputDelta : tu.getString("_inputRaw") + inputDelta);
                    }
                }

                @Override
                public void onToolUseEnd(String toolUseId) {
                    JSONObject tu = toolUseBuffers.get(toolUseId);
                    if (tu != null) {
                        String raw = tu.getString("_inputRaw");
                        if (raw != null) {
                            try {
                                tu.put("input", JSONObject.parseObject(raw));
                            } catch (Exception e) {
                                tu.put("input", JSONObject.of("raw", raw));
                            }
                            tu.remove("_inputRaw");
                        }
                        toolUses.add(tu);
                    }
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

            // 从 contextUsagePercentage 推算 token
            // thinking + 正文都计入 output
            if (traceCtx.inputTokens() == 0 && contextUsagePct[0] > 0) {
                int totalOutputLen = contentBuilder.length() + thinkingBuilder.length();
                int outputTokens = Math.max(1, totalOutputLen / 4);
                int totalTokens = (int) (contextUsagePct[0] / 100.0 * maxTokensVal);
                int inputTokens = Math.max(0, totalTokens - outputTokens);
                traceCtx.recordTokenUsage(inputTokens, outputTokens, traceCtx.credits());
            }

            String stopReason = toolUses.isEmpty() ? "end_turn" : "tool_use";
            String thinkingContent = thinkingBuilder.isEmpty() ? null : thinkingBuilder.toString();
            JSONObject response = translator.toClaudeResponse(
                    contentBuilder.toString(), thinkingContent,
                    toolUses.isEmpty() ? null : toolUses,
                    traceCtx.inputTokens(), traceCtx.outputTokens(),
                    resolved.requestedModel(), stopReason
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
            if (!"authorization".equalsIgnoreCase(k) && !"x-api-key".equalsIgnoreCase(k)) {
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
