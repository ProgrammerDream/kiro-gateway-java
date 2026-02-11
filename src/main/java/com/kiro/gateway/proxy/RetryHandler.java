package com.kiro.gateway.proxy;

import com.kiro.gateway.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 自动重试 + 指数退避
 * <p>
 * 对 429/5xx 错误自动重试，delay × 2^attempt
 */
@Component
public class RetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);

    private final int maxRetries;
    private final long baseDelayMs;

    public RetryHandler(AppProperties properties) {
        this.maxRetries = properties.retry().maxRetries();
        this.baseDelayMs = properties.retry().baseDelayMs();
    }

    /**
     * 判断是否应该重试
     */
    public boolean shouldRetry(int statusCode, int attempt) {
        if (attempt >= maxRetries) {
            return false;
        }
        // 429 (Rate Limit) 或 5xx (服务器错误) 可重试
        // 401/403 (认证错误) 不重试
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * 计算重试延迟（毫秒）
     */
    public long getDelay(int attempt) {
        return (long) (baseDelayMs * Math.pow(2, attempt));
    }

    /**
     * 执行重试等待
     */
    public void waitBeforeRetry(int attempt, int statusCode) {
        long delay = getDelay(attempt);
        log.warn("请求失败(status={}), 第{}次重试, 等待{}ms", statusCode, attempt + 1, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int maxRetries() {
        return maxRetries;
    }
}
