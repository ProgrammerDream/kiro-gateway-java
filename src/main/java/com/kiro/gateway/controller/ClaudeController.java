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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;

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
    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> messages(@RequestBody String body, ServerWebExchange exchange) {
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

        if (stream) {
            return Mono.just(streamResponse(payload, accessToken, traceCtx, account, resolved, translated.toolNameMap(), exchange));
        }

        // 非流式
        return Mono.fromCallable(() -> {
            NonStreamResult result = callNonStream(payload, accessToken, traceCtx, account, resolved, translated.toolNameMap());
            return (Object) result.response.toJSONString();
        }).doOnSuccess(r -> {
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        });
    }

    // ==================== 流式响应 ====================

    private Flux<String> streamResponse(String payload, String accessToken,
                                         TraceContext traceCtx, Account account,
                                         ModelResolver.ResolveResult resolved,
                                         Map<String, String> toolNameMap,
                                         ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, String> reverseToolMap = reverseMap(toolNameMap);
        ThinkingParser thinkingParser = resolved.thinking() ? new ThinkingParser() : null;

        // 发送 message_start
        emitEvent(sink, "message_start", JSONObject.of("type", "message_start", //
                "message", JSONObject.of("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), //
                        "type", "message", //
                        "role", "assistant", //
                        "model", resolved.requestedModel() //
                )));

        // 发送 content_block_start (text)
        emitEvent(sink, "content_block_start", JSONObject.of("type", "content_block_start", //
                "index", 0, //
                "content_block", JSONObject.of("type", "text", "text", "")));

        final int[] blockIndex = {0};
        final boolean[] hasToolUse = {false};

        new Thread(() -> {
            try {
                kiroClient.callStream(payload, accessToken, traceCtx, new StreamCallback() {
                    @Override
                    public void onText(String text) {
                        if (thinkingParser != null) {
                            ThinkingParser.ParseResult parsed = thinkingParser.feed(text);
                            if (parsed.hasContent()) {
                                emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                        "index", blockIndex[0], //
                                        "delta", JSONObject.of("type", "text_delta", "text", parsed.contentDelta())));
                            }
                        } else {
                            emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                    "index", blockIndex[0], //
                                    "delta", JSONObject.of("type", "text_delta", "text", text)));
                        }
                    }

                    @Override
                    public void onThinking(String thinking) {
                        // Claude thinking 格式
                    }

                    @Override
                    public void onToolUseStart(String toolUseId, String name) {
                        hasToolUse[0] = true;
                        String originalName = reverseToolMap.getOrDefault(name, name);

                        // 结束当前 text block
                        emitEvent(sink, "content_block_stop", JSONObject.of("type", "content_block_stop", //
                                "index", blockIndex[0]));

                        blockIndex[0]++;

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
                    public void onComplete() {
                        if (thinkingParser != null) {
                            ThinkingParser.ParseResult last = thinkingParser.finish();
                            if (last.hasContent()) {
                                emitEvent(sink, "content_block_delta", JSONObject.of("type", "content_block_delta", //
                                        "index", blockIndex[0], //
                                        "delta", JSONObject.of("type", "text_delta", "text", last.contentDelta())));
                            }
                        }

                        // 结束最后一个 block
                        emitEvent(sink, "content_block_stop", JSONObject.of("type", "content_block_stop", //
                                "index", blockIndex[0]));

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
        Map<String, String> reverseToolMap = reverseMap(toolNameMap);
        JSONArray toolUses = new JSONArray();
        ThinkingParser thinkingParser = resolved.thinking() ? new ThinkingParser() : null;

        Map<String, JSONObject> toolUseBuffers = new LinkedHashMap<>();

        try {
            kiroClient.callStream(payload, accessToken, traceCtx, new StreamCallback() {
                @Override
                public void onText(String text) {
                    if (thinkingParser != null) {
                        ThinkingParser.ParseResult parsed = thinkingParser.feed(text);
                        if (parsed.hasContent()) {
                            contentBuilder.append(parsed.contentDelta());
                        }
                    } else {
                        contentBuilder.append(text);
                    }
                }

                @Override
                public void onThinking(String thinking) {}

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
                public void onComplete() {
                    if (thinkingParser != null) {
                        ThinkingParser.ParseResult last = thinkingParser.finish();
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

            String stopReason = toolUses.isEmpty() ? "end_turn" : "tool_use";
            JSONObject response = translator.toClaudeResponse(
                    contentBuilder.toString(), toolUses.isEmpty() ? null : toolUses,
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
