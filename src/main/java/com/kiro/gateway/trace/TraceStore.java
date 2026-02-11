package com.kiro.gateway.trace;

import com.kiro.gateway.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final DatabaseConfig db;
    private final BlockingQueue<TraceLog> recentTraces = new ArrayBlockingQueue<>(BUFFER_SIZE);

    public TraceStore(DatabaseConfig db) {
        this.db = db;
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

        // 同时写入请求日志表
        db.insertRequestLog(
                traceLog.traceId(), traceLog.apiType(), traceLog.model(),
                traceLog.accountId(), accountName,
                traceLog.inputTokens(), traceLog.outputTokens(), traceLog.credits(),
                traceLog.durationMs(), traceLog.success(), traceLog.errorMessage(),
                traceLog.apiKey(), traceLog.stream(), traceLog.kiroEndpoint()
        );
    }

    /**
     * 获取追踪详情
     */
    public DatabaseConfig.TraceRow getTrace(String traceId) {
        return db.getTraceByTraceId(traceId);
    }

    private void persistAsync(TraceLog t) {
        // 当前使用同步写入，后续可改为异步队列
        try {
            db.insertTrace(
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
