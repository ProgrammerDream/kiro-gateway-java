package com.kiro.gateway.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.config.DatabaseConfig;
import com.kiro.gateway.model.ModelResolver;
import com.kiro.gateway.pool.Account;
import com.kiro.gateway.pool.AccountPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理面板后端 API
 */
@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AccountPool accountPool;
    private final DatabaseConfig db;
    private final AppProperties properties;
    private final ModelResolver modelResolver;

    // SSE 事件推送
    private final Sinks.Many<ServerSentEvent<String>> eventSink =
            Sinks.many().multicast().onBackpressureBuffer(256);

    public AdminController(AccountPool accountPool, DatabaseConfig db,
                           AppProperties properties, ModelResolver modelResolver) {
        this.accountPool = accountPool;
        this.db = db;
        this.properties = properties;
        this.modelResolver = modelResolver;
    }

    // ==================== 登录 ====================

    @PostMapping("/login")
    public Mono<String> login(@RequestBody String body) {
        JSONObject req = JSONObject.parseObject(body);
        String password = req.getString("password");
        if (properties.getAdminPassword().equals(password)) {
            return Mono.just(JSONObject.of("success", true, "token", "admin-session").toJSONString());
        }
        return Mono.just(JSONObject.of("success", false, "message", "密码错误").toJSONString());
    }

    // ==================== 仪表盘 ====================

    @GetMapping("/dashboard")
    public Mono<String> dashboard() {
        AccountPool.PoolStats stats = accountPool.getStats();
        int requestLogCount = db.getRequestLogCount();

        JSONObject result = new JSONObject();
        result.put("accounts", JSONObject.of( //
                "total", stats.total(), //
                "active", stats.active(), //
                "cooldown", stats.cooldown() //
        ));
        result.put("requests", JSONObject.of( //
                "total", stats.totalRequests(), //
                "errors", stats.totalErrors(), //
                "logCount", requestLogCount //
        ));
        return Mono.just(result.toJSONString());
    }

    // ==================== 账号管理 ====================

    @GetMapping("/accounts")
    public Mono<String> listAccounts() {
        List<Account> accounts = accountPool.listAccounts();
        JSONArray arr = new JSONArray();
        for (Account a : accounts) {
            JSONObject item = new JSONObject();
            item.put("id", a.id());
            item.put("name", a.name());
            item.put("authMethod", a.authMethod());
            item.put("status", a.status());
            item.put("requestCount", a.requestCount());
            item.put("successCount", a.successCount());
            item.put("errorCount", a.errorCount());
            item.put("consecutiveErrors", a.consecutiveErrors());
            item.put("inputTokensTotal", a.inputTokensTotal());
            item.put("outputTokensTotal", a.outputTokensTotal());
            item.put("creditsTotal", a.creditsTotal());
            item.put("cooldownUntil", a.cooldownUntil() != null ? a.cooldownUntil().toString() : null);
            item.put("lastUsedAt", a.lastUsedAt() != null ? a.lastUsedAt().toString() : null);
            item.put("createdAt", a.createdAt().toString());
            arr.add(item);
        }
        return Mono.just(arr.toJSONString());
    }

    @PostMapping("/accounts")
    public Mono<String> addAccount(@RequestBody String body) {
        JSONObject req = JSONObject.parseObject(body);
        String name = req.getString("name");
        String credentials = req.getString("credentials");
        String authMethod = req.getString("authMethod");
        if (authMethod == null) authMethod = "social";

        String id = accountPool.addAccount(name, credentials, authMethod);
        publishEvent("account_added", JSONObject.of("id", id, "name", name));
        return Mono.just(JSONObject.of("success", true, "id", id).toJSONString());
    }

    @DeleteMapping("/accounts/{id}")
    public Mono<String> deleteAccount(@PathVariable String id) {
        boolean removed = accountPool.removeAccount(id);
        if (removed) {
            publishEvent("account_removed", JSONObject.of("id", id));
        }
        return Mono.just(JSONObject.of("success", removed).toJSONString());
    }

    // ==================== 请求日志 ====================

    @GetMapping("/request-logs")
    public Mono<String> getRequestLogs(@RequestParam(defaultValue = "20") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        List<DatabaseConfig.RequestLogRow> logs = db.getRequestLogs(limit, offset);
        int total = db.getRequestLogCount();

        JSONArray arr = new JSONArray();
        for (DatabaseConfig.RequestLogRow r : logs) {
            JSONObject item = new JSONObject();
            item.put("id", r.id());
            item.put("timestamp", r.timestamp());
            item.put("traceId", r.traceId());
            item.put("apiType", r.apiType());
            item.put("model", r.model());
            item.put("accountId", r.accountId());
            item.put("accountName", r.accountName());
            item.put("inputTokens", r.inputTokens());
            item.put("outputTokens", r.outputTokens());
            item.put("credits", r.credits());
            item.put("durationMs", r.durationMs());
            item.put("success", r.success());
            item.put("errorMessage", r.errorMessage());
            item.put("stream", r.stream());
            item.put("endpoint", r.endpoint());
            arr.add(item);
        }

        JSONObject result = JSONObject.of("data", arr, "total", total);
        return Mono.just(result.toJSONString());
    }

    // ==================== 追踪详情 ====================

    @GetMapping("/traces/{traceId}")
    public Mono<String> getTrace(@PathVariable String traceId) {
        DatabaseConfig.TraceRow trace = db.getTraceByTraceId(traceId);
        if (trace == null) {
            return Mono.just(JSONObject.of("error", "追踪记录不存在").toJSONString());
        }

        JSONObject result = new JSONObject();
        result.put("traceId", trace.traceId());
        result.put("timestamp", trace.timestamp());
        result.put("apiType", trace.apiType());
        result.put("model", trace.model());
        result.put("accountId", trace.accountId());
        result.put("durationMs", trace.durationMs());
        result.put("success", trace.success());
        result.put("inputTokens", trace.inputTokens());
        result.put("outputTokens", trace.outputTokens());
        result.put("credits", trace.credits());
        result.put("errorMessage", trace.errorMessage());

        // 四阶段数据（JSON 字符串，前端解析后可视化）
        result.put("clientRequest", trace.clientRequest());
        result.put("clientHeaders", trace.clientHeaders());
        result.put("kiroRequest", trace.kiroRequest());
        result.put("kiroEndpoint", trace.kiroEndpoint());
        result.put("kiroHeaders", trace.kiroHeaders());
        result.put("kiroStatus", trace.kiroStatus());
        result.put("kiroEvents", trace.kiroEvents());
        result.put("clientResponse", trace.clientResponse());
        result.put("clientStatus", trace.clientStatus());

        return Mono.just(result.toJSONString());
    }

    // ==================== 模型管理 ====================

    @GetMapping("/models")
    public Mono<String> listModels() {
        List<ModelResolver.ModelInfo> models = modelResolver.listModels();
        JSONArray arr = new JSONArray();
        for (ModelResolver.ModelInfo m : models) {
            arr.add(JSONObject.of( //
                    "id", m.id, //
                    "displayName", m.displayName, //
                    "maxTokens", m.maxTokens, //
                    "ownedBy", m.ownedBy //
            ));
        }
        return Mono.just(arr.toJSONString());
    }

    // ==================== 应用日志 ====================

    @GetMapping("/app-logs")
    public Mono<String> getAppLogs(@RequestParam(defaultValue = "100") int lines) {
        String logPath = properties.getLogging().getFilePath() + "/kiro-gateway.log";
        File logFile = new File(logPath);
        if (!logFile.exists()) {
            return Mono.just(JSONObject.of("lines", new JSONArray()).toJSONString());
        }

        List<String> logLines = tailFile(logFile, lines);
        JSONArray arr = new JSONArray();
        arr.addAll(logLines);
        return Mono.just(JSONObject.of("lines", arr).toJSONString());
    }

    @GetMapping(value = "/app-logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamAppLogs() {
        String logPath = properties.getLogging().getFilePath() + "/kiro-gateway.log";
        File logFile = new File(logPath);

        return Flux.interval(Duration.ofSeconds(1))
                .map(tick -> {
                    if (!logFile.exists()) {
                        return ServerSentEvent.<String>builder().data("").build();
                    }
                    List<String> newLines = tailFile(logFile, 5);
                    String data = String.join("\n", newLines);
                    return ServerSentEvent.<String>builder().data(data).build();
                });
    }

    // ==================== SSE 事件推送 ====================

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseEvents() {
        return eventSink.asFlux()
                .mergeWith(Flux.interval(Duration.ofSeconds(30))
                        .map(t -> ServerSentEvent.<String>builder().comment("heartbeat").build()));
    }

    // ==================== API Key 管理 ====================

    @GetMapping("/api-keys")
    public Mono<String> listApiKeys() {
        List<DatabaseConfig.ApiKeyRow> keys = db.getAllApiKeys();
        JSONArray arr = new JSONArray();
        for (DatabaseConfig.ApiKeyRow k : keys) {
            arr.add(JSONObject.of("key", k.key(), "name", k.name(), "createdAt", k.createdAt()));
        }
        return Mono.just(arr.toJSONString());
    }

    // ==================== 辅助方法 ====================

    private void publishEvent(String type, JSONObject data) {
        data.put("eventType", type);
        eventSink.tryEmitNext(ServerSentEvent.<String>builder()
                .event(type)
                .data(data.toJSONString())
                .build());
    }

    private List<String> tailFile(File file, int lines) {
        List<String> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) return result;

            // 从文件末尾回扫，找到足够的换行符
            long pos = fileLength - 1;
            int count = 0;
            while (pos > 0 && count <= lines) {
                raf.seek(pos);
                if (raf.read() == '\n') {
                    count++;
                }
                pos--;
            }

            // 定位到起始读取位置
            long startPos = (count > lines) ? pos + 2 : 0;
            raf.seek(startPos);
            int bytesToRead = (int) (fileLength - startPos);
            byte[] buf = new byte[bytesToRead];
            raf.readFully(buf);

            // UTF-8 解码后按行分割
            String content = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            String[] splitLines = content.split("\n", -1);
            for (String line : splitLines) {
                if (!line.isEmpty()) {
                    result.add(line);
                }
            }
        } catch (Exception e) {
            log.warn("读取日志文件失败: {}", e.getMessage());
        }
        return result;
    }
}
