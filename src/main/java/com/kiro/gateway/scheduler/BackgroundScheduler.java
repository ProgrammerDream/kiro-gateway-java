package com.kiro.gateway.scheduler;

import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.dao.RequestLogDAO;
import com.kiro.gateway.dao.TraceDAO;
import com.kiro.gateway.model.ModelResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时任务调度器
 * <p>
 * - 请求日志自动清理
 * - 追踪日志自动清理
 * - 模型缓存刷新
 */
@Component
public class BackgroundScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackgroundScheduler.class);

    private final AppProperties properties;
    private final RequestLogDAO requestLogDAO;
    private final TraceDAO traceDAO;
    private final ModelResolver modelResolver;

    public BackgroundScheduler(AppProperties properties, RequestLogDAO requestLogDAO,
                               TraceDAO traceDAO, ModelResolver modelResolver) {
        this.properties = properties;
        this.requestLogDAO = requestLogDAO;
        this.traceDAO = traceDAO;
        this.modelResolver = modelResolver;
    }

    /**
     * 请求日志清理（每天凌晨 3 点）
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupRequestLogs() {
        int retention = properties.getLogging().getRequestLogRetention();
        int deleted = requestLogDAO.cleanup(retention);
        if (deleted > 0) {
            log.info("清理请求日志: 删除 {} 条, 保留最近 {} 条", deleted, retention);
        }
    }

    /**
     * 追踪日志清理（每天凌晨 3 点 10 分）
     */
    @Scheduled(cron = "0 10 3 * * ?")
    public void cleanupTraces() {
        int retention = properties.getLogging().getTraceRetention();
        int deleted = traceDAO.cleanup(retention);
        if (deleted > 0) {
            log.info("清理追踪日志: 删除 {} 条, 保留最近 {} 条", deleted, retention);
        }
    }

    /**
     * 模型缓存刷新（每小时）
     */
    @Scheduled(fixedRate = 3600000)
    public void refreshModelCache() {
        try {
            modelResolver.refresh();
        } catch (Exception e) {
            log.error("模型缓存刷新失败", e);
        }
    }
}
