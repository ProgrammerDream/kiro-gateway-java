package com.kiro.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用配置属性绑定
 */
@Data
@Component
@ConfigurationProperties(prefix = "kiro")
public class AppProperties {

    private String adminPassword = "admin123";
    private String apiKey = "sk-kiro-default";
    private boolean requireApiKey = true;
    private String region = "us-east-1";
    private String kiroVersion = "0.8.0";
    private String poolStrategy = "round-robin";
    private ProxyConfig proxy = new ProxyConfig();
    private CooldownConfig cooldown = new CooldownConfig();
    private ThinkingConfig thinking = new ThinkingConfig();
    private List<String> endpoints = List.of(
            "https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse",
            "https://q.us-east-1.amazonaws.com/generateAssistantResponse"
    );
    private LoggingConfig logging = new LoggingConfig();
    private DatabaseConfig database = new DatabaseConfig();
    private RetryConfig retry = new RetryConfig();

    // --- 嵌套配置类 ---

    @Data
    public static class ProxyConfig {
        private boolean enabled = false;
        private String url = "";
    }

    @Data
    public static class CooldownConfig {
        private int quotaMinutes = 60;
        private int errorMinutes = 1;
        private int errorThreshold = 3;
    }

    @Data
    public static class ThinkingConfig {
        // 是否默认开启 thinking（对齐 Python 版 FAKE_REASONING_ENABLED）
        private boolean enabled = true;
        private String suffix = "-thinking";
        private String outputFormat = "xml";
        private String prompt = "<thinking_mode>enabled</thinking_mode>";
        private int maxTokens = 4000;
    }

    @Data
    public static class LoggingConfig {
        private String filePath = "data/logs";
        private String maxFileSize = "100MB";
        private int maxHistory = 30;
        private String totalSizeCap = "1GB";
        private int requestLogRetention = 100000;
        private String requestLogCleanupCron = "0 0 3 * * ?";
        private int traceRetention = 50000;
        private String traceCleanupCron = "0 0 3 * * ?";
    }

    @Data
    public static class DatabaseConfig {
        private String path = "data/kiro.db";
    }

    @Data
    public static class RetryConfig {
        private int maxRetries = 3;
        private long baseDelayMs = 1000;
    }
}
