package com.kiro.gateway.trace;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.dao.RequestLogDAO;
import com.kiro.gateway.dao.TraceDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 追踪日志存储
 * <p>
 * 内存环形缓冲 + SQLite 异步持久化
 */
@Component
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
    private static final int BUFFER_SIZE = 1000;

    private final RequestLogDAO requestLogDAO;
    private final TraceDAO traceDAO;
    private final BlockingQueue<TraceLog> recentTraces = new ArrayBlockingQueue<>(BUFFER_SIZE);

    public TraceStore(RequestLogDAO requestLogDAO, TraceDAO traceDAO) {
        this.requestLogDAO = requestLogDAO;
        this.traceDAO = traceDAO;
    }

    /**
     * 保存追踪记录（同时写入内存缓冲和数据库）
     */
    public void save(TraceLog traceLog) {
        // 内存环形缓冲：满了则移除最旧的
        if (!recentTraces.offer(traceLog)) {
            recentTraces.poll();
            recentTraces.offer(traceLog);
        }

        // 异步写入数据库
        persistAsync(traceLog);
    }

    /**
     * 保存追踪和请求日志
     */
    public void saveWithRequestLog(TraceLog traceLog, String accountName) {
        save(traceLog);

        String conversationId = extractConversationId(traceLog.clientRequest(), traceLog.apiKey());
        requestLogDAO.insert(
                traceLog.traceId(), traceLog.apiType(), traceLog.model(),
                traceLog.accountId(), accountName,
                traceLog.inputTokens(), traceLog.outputTokens(), traceLog.credits(),
                traceLog.durationMs(), traceLog.success(), traceLog.errorMessage(),
                traceLog.apiKey(), traceLog.stream(), traceLog.kiroEndpoint(),
                conversationId
        );
    }

    /**
     * 从 clientRequest 中提取 conversation_id
     * <p>
     * 取第一条 role=user 的消息内容，与 apiKey 拼接做 SHA-256 取前 12 位
     */
    public static String extractConversationId(String clientRequest, String apiKey) {
        if (clientRequest == null || clientRequest.isEmpty()) return null;
        try {
            JSONObject req = JSONObject.parseObject(clientRequest);
            JSONArray messages = req.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) return null;

            // 找第一条 user 消息
            String firstUserContent = null;
            for (int i = 0; i < messages.size(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if ("user".equals(msg.getString("role"))) {
                    Object content = msg.get("content");
                    // content 可能是 string 或 array
                    firstUserContent = content instanceof String
                            ? (String) content
                            : content.toString();
                    break;
                }
            }
            if (firstUserContent == null) return null;

            String seed = (apiKey != null ? apiKey : "") + "||" + firstUserContent;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("提取 conversationId 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取追踪详情
     */
    public TraceDAO.TraceRow getTrace(String traceId) {
        return traceDAO.findByTraceId(traceId);
    }

    private void persistAsync(TraceLog t) {
        // 当前使用同步写入，后续可改为异步队列
        try {
            traceDAO.insert(
                    t.traceId(), t.apiType(), t.model(), t.accountId(),
                    t.durationMs(), t.success(),
                    t.clientRequest(), t.clientHeaders(),
                    t.kiroRequest(), t.kiroEndpoint(), t.kiroHeaders(),
                    t.kiroStatus(), t.kiroEventsJson(),
                    t.inputTokens(), t.outputTokens(), t.credits(),
                    t.clientResponse(), t.clientStatus(), t.errorMessage()
            );
        } catch (Exception e) {
            log.error("持久化追踪日志失败: traceId={}", t.traceId(), e);
        }
    }
}
